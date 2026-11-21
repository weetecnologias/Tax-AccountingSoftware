package com.taxpilot.accounting;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.accounting.AccountingDtos.AccountDto;
import com.taxpilot.accounting.AccountingDtos.AccountRequest;
import com.taxpilot.accounting.AccountingRepositories.LedgerAccountRepository;
import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.ClientService;
import com.taxpilot.common.ApiException;

import tools.jackson.databind.json.JsonMapper;

@Service
public class ChartOfAccountsService {

    static final String TEMPLATE_RESOURCE = "accounting/coa-template-in-v1.json";

    private final LedgerAccountRepository accounts;
    private final ClientService clients;
    private final AuditService audit;
    private final JsonMapper json;

    public ChartOfAccountsService(LedgerAccountRepository accounts, ClientService clients, AuditService audit,
                                  JsonMapper json) {
        this.accounts = accounts;
        this.clients = clients;
        this.audit = audit;
        this.json = json;
    }

    /** "group" is optional in the template file and defaults to false. */
    record TemplateAccount(String code, String name, String type,
                           @com.fasterxml.jackson.annotation.JsonProperty("group") Boolean groupFlag,
                           String parent, String subtype) {
        boolean group() {
            return Boolean.TRUE.equals(groupFlag);
        }
    }

    record Template(String templateId, int version, String description, List<TemplateAccount> accounts) {
    }

    @Transactional(readOnly = true)
    public List<AccountDto> list(UUID clientId) {
        clients.find(clientId);
        return accounts.findByClientIdOrderByCodeAsc(clientId).stream().map(this::toDto).toList();
    }

    @Transactional
    public List<AccountDto> applyTemplate(UUID clientId) {
        clients.findOpen(clientId);
        if (accounts.existsByClientId(clientId)) {
            throw ApiException.conflict("COA_EXISTS",
                    "This client already has a chart of accounts. Add or edit accounts individually.");
        }
        Template template = loadTemplate();
        Map<String, UUID> idsByCode = new HashMap<>();
        for (TemplateAccount t : template.accounts()) {
            LedgerAccount a = new LedgerAccount(clientId, t.code(), t.name(), AccountType.valueOf(t.type()), t.group(),
                    template.templateId() + ":" + t.code());
            a.setSubtype(t.subtype());
            a.setCashFlowCategory(LedgerAccount.defaultCashFlowCategory(a.getAccountType(), t.subtype(),
                    a.getTemplateCode(), t.group()));
            if (t.parent() != null) {
                UUID parentId = idsByCode.get(t.parent());
                if (parentId == null) {
                    throw new IllegalStateException("Template parent must precede child: " + t.code());
                }
                a.setParentId(parentId);
            }
            accounts.save(a);
            idsByCode.put(t.code(), a.getId());
        }
        audit.record("COA_TEMPLATE_APPLIED", "CLIENT", clientId, clientId,
                Map.of("template", template.templateId(), "version", template.version(),
                        "accounts", template.accounts().size()));
        return list(clientId);
    }

    @Transactional
    public AccountDto create(AccountRequest req) {
        clients.findOpen(req.clientId());
        String code = req.code().trim().toUpperCase();
        if (accounts.findByClientIdAndCode(req.clientId(), code).isPresent()) {
            throw ApiException.field("code", "This account code is already used for this client");
        }
        LedgerAccount a = new LedgerAccount(req.clientId(), code, req.name().trim(), req.accountType(), req.group(), null);
        applyCommon(a, req);
        accounts.save(a);
        audit.record("ACCOUNT_CREATED", "LEDGER_ACCOUNT", a.getId(), req.clientId(),
                Map.of("code", a.getCode(), "name", a.getName(), "type", a.getAccountType().name()));
        return toDto(a);
    }

    @Transactional
    public AccountDto update(UUID id, AccountRequest req) {
        LedgerAccount a = find(id);
        if (!a.getClientId().equals(req.clientId())) {
            throw ApiException.notFound("Account");
        }
        clients.findOpen(a.getClientId());
        if (req.version() == null || req.version() != a.getVersion()) {
            throw ApiException.conflict("STALE_VERSION", "This record was changed by someone else. Reload and try again.");
        }
        String code = req.code().trim().toUpperCase();
        accounts.findByClientIdAndCode(a.getClientId(), code).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> {
                    throw ApiException.field("code", "This account code is already used for this client");
                });
        boolean used = accounts.isUsed(id);
        if (used && (req.accountType() != a.getAccountType() || req.group() != a.isGroup())) {
            throw ApiException.conflict("ACCOUNT_IN_USE",
                    "The type or group setting of an account with postings cannot change");
        }
        if (a.isGroup() && !req.group() && accounts.countByParentId(id) > 0) {
            throw ApiException.field("group", "Move this group's child accounts before turning it into a ledger");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!a.getCode().equals(code)) changes.put("code", code);
        if (!a.getName().equals(req.name().trim())) changes.put("name", req.name().trim());
        if (!Objects.equals(a.getParentId(), req.parentId())) changes.put("parentId", String.valueOf(req.parentId()));
        boolean active = req.active() == null || req.active();
        if (a.isActive() != active) changes.put("active", active);
        a.setCode(code);
        a.setName(req.name().trim());
        a.setAccountType(req.accountType());
        a.setGroup(req.group());
        applyCommon(a, req);
        a.setActive(active);
        accounts.save(a);
        if (!changes.isEmpty()) {
            audit.record("ACCOUNT_UPDATED", "LEDGER_ACCOUNT", id, a.getClientId(), changes);
        }
        return toDto(a);
    }

    public LedgerAccount find(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account"));
    }

    private void applyCommon(LedgerAccount a, AccountRequest req) {
        if (req.parentId() != null) {
            LedgerAccount parent = accounts.findById(req.parentId())
                    .filter(p -> p.getClientId().equals(a.getClientId()))
                    .orElseThrow(() -> ApiException.field("parentId", "Parent group not found"));
            if (!parent.isGroup()) {
                throw ApiException.field("parentId", "The parent must be a group account");
            }
            if (parent.getAccountType() != req.accountType()) {
                throw ApiException.field("parentId", "The parent group must have the same account type");
            }
            // Prevent cycles: walk up from the parent.
            UUID cursor = parent.getId();
            int guard = 0;
            while (cursor != null && guard++ < 50) {
                if (cursor.equals(a.getId())) {
                    throw ApiException.field("parentId", "An account cannot be placed under itself");
                }
                cursor = accounts.findById(cursor).map(LedgerAccount::getParentId).orElse(null);
            }
        }
        a.setParentId(req.parentId());
        a.setSubtype(req.subtype() == null || req.subtype().isBlank() ? null : req.subtype().trim().toUpperCase());
        a.setDescription(req.description() == null || req.description().isBlank() ? null : req.description().trim());
        boolean pl = req.accountType() == AccountType.INCOME || req.accountType() == AccountType.EXPENSE;
        if (req.group() || pl) {
            a.setCashFlowCategory(null);
        } else if (req.cashFlowCategory() != null && !req.cashFlowCategory().isBlank()) {
            a.setCashFlowCategory(req.cashFlowCategory());
        } else {
            a.setCashFlowCategory(LedgerAccount.defaultCashFlowCategory(req.accountType(), a.getSubtype(),
                    a.getTemplateCode(), false));
        }
    }

    Template loadTemplate() {
        try (InputStream in = new ClassPathResource(TEMPLATE_RESOURCE).getInputStream()) {
            return json.readValue(in, Template.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    AccountDto toDto(LedgerAccount a) {
        return new AccountDto(a.getId(), a.getCode(), a.getName(), a.getAccountType(), a.getSubtype(), a.getParentId(),
                a.isGroup(), a.isActive(), a.getTemplateCode(), a.getDescription(), accounts.isUsed(a.getId()),
                a.getCashFlowCategory(), a.getVersion());
    }
}
