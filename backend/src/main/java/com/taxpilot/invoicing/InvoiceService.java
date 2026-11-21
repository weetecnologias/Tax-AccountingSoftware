package com.taxpilot.invoicing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.accounting.AccountingPeriod;
import com.taxpilot.accounting.AccountingRepositories.LedgerAccountRepository;
import com.taxpilot.accounting.JournalEntry;
import com.taxpilot.accounting.JournalService;
import com.taxpilot.accounting.LedgerAccount;
import com.taxpilot.accounting.Money;
import com.taxpilot.accounting.PeriodService;
import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.ClientRecords;
import com.taxpilot.clients.ClientRepositories.GstRegistrationRepository;
import com.taxpilot.clients.ClientService;
import com.taxpilot.clients.ClientStatus;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.PageResponse;
import com.taxpilot.common.validation.IndianStates;
import com.taxpilot.invoicing.InvoicingRepositories.InvoiceRepository;
import com.taxpilot.invoicing.InvoicingRepositories.PartyRepository;
import com.taxpilot.security.CurrentUser;
import com.taxpilot.settings.SettingsService;
import com.taxpilot.users.UserDtos.UserRef;
import com.taxpilot.users.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Sales and purchase invoices: DRAFT → submit → approve & post (creates a posted journal) or reject / cancel.
 * Approval follows the organisation's maker-checker policy.
 */
@Service
public class InvoiceService {

    static final String TPL = "IN-STANDARD:";

    // ---------------------------------------------------------------- DTOs

    public record LineRequest(@NotBlank @Size(max = 300) String description,
                              @Pattern(regexp = "^$|^[0-9]{4,8}$", message = "HSN/SAC must be 4 to 8 digits") String hsnSac,
                              @NotNull UUID accountId, @NotBlank String quantity, @NotBlank String unitPrice,
                              @NotBlank String gstRate) {
    }

    public record InvoiceRequest(@NotNull UUID clientId, @NotNull Invoice.Type invoiceType,
                                 @NotNull LocalDate invoiceDate, LocalDate dueDate, @NotNull UUID partyId,
                                 UUID gstRegistrationId, String placeOfSupply, Boolean reverseCharge,
                                 @Size(max = 16) String supplierInvoiceNumber, @Size(max = 1000) String notes,
                                 @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines, Long version,
                                 UUID originalInvoiceId, @Size(max = 300) String noteReason) {
    }

    public record LineDto(UUID id, int lineNo, String description, String hsnSac, UUID accountId, String accountCode,
                          String accountName, String quantity, String unitPrice, String taxableValue, String gstRate,
                          String cgst, String sgst, String igst) {
    }

    public record InvoiceDto(UUID id, UUID clientId, Invoice.Type invoiceType, Invoice.Status status,
                             String invoiceNumber, String supplierInvoiceNumber, LocalDate invoiceDate,
                             LocalDate dueDate, UUID partyId, String partyName, String partyGstin,
                             UUID gstRegistrationId, String gstin, String placeOfSupply, String placeOfSupplyName,
                             Invoice.SupplyType supplyType, boolean reverseCharge, String taxableTotal,
                             String cgstTotal, String sgstTotal, String igstTotal, String grandTotal, String notes,
                             UUID journalEntryId, String journalNumber, UserRef preparedBy, UserRef submittedBy,
                             Instant submittedAt, UserRef approvedBy, Instant approvedAt, boolean selfApproved,
                             String rejectionReason, List<LineDto> lines, List<String> allowedActions,
                             Instant createdAt, long version, UUID originalInvoiceId, String originalNumber,
                             String noteReason, String notesPostedTotal, String noteableBalance) {
    }

    public record SeriesDto(UUID id, String kind, UUID gstRegistrationId, String gstin, String prefix,
                            String nextNumberPreview) {
    }

    public record RateDto(String rate, String description, String reviewStatus) {
    }

    public record CreateResult(InvoiceDto invoice, boolean created) {
    }

    // ---------------------------------------------------------------- wiring

    private final InvoiceRepository invoices;
    private final PartyRepository parties;
    private final LedgerAccountRepository accounts;
    private final GstRegistrationRepository gstRegs;
    private final ClientService clients;
    private final PeriodService periods;
    private final JournalService journals;
    private final SettingsService settings;
    private final UserRepository users;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public InvoiceService(InvoiceRepository invoices, PartyRepository parties, LedgerAccountRepository accounts,
                          GstRegistrationRepository gstRegs, ClientService clients, PeriodService periods,
                          JournalService journals, SettingsService settings, UserRepository users,
                          AuditService audit, JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.parties = parties;
        this.accounts = accounts;
        this.gstRegs = gstRegs;
        this.clients = clients;
        this.periods = periods;
        this.journals = journals;
        this.settings = settings;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public PageResponse<InvoiceDto> list(UUID clientId, Invoice.Type type, Invoice.Status status, String q,
                                         Pageable pageable) {
        clients.find(clientId);
        Specification<Invoice> spec = (r, query, cb) -> cb.equal(r.get("clientId"), clientId);
        if (type != null) spec = spec.and((r, query, cb) -> cb.equal(r.get("invoiceType"), type));
        if (status != null) spec = spec.and((r, query, cb) -> cb.equal(r.get("status"), status));
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((r, query, cb) -> cb.or(cb.like(cb.lower(r.get("invoiceNumber")), like),
                    cb.like(cb.lower(r.get("supplierInvoiceNumber")), like)));
        }
        return PageResponse.of(invoices.findAll(spec, pageable), i -> toDto(i, false));
    }

    @Transactional(readOnly = true)
    public InvoiceDto get(UUID id) {
        return toDto(find(id), true);
    }

    @Transactional(readOnly = true)
    public List<RateDto> rates(LocalDate date) {
        return jdbc.query("""
                select rate, description, review_status from gst_rate_slabs
                where effective_from <= ? and (effective_to is null or effective_to >= ?) and review_status <> 'RETIRED'
                order by rate
                """, (rs, i) -> new RateDto(rs.getBigDecimal("rate").toPlainString(), rs.getString("description"),
                rs.getString("review_status")), date, date);
    }

    @Transactional
    public List<SeriesDto> series(UUID clientId) {
        clients.find(clientId);
        List<SeriesDto> out = new ArrayList<>();
        List<ClientRecords.GstRegistration> regList = gstRegs.findByClientIdOrderByCreatedAtAsc(clientId);
        for (String kind : List.of("INVOICE", "CREDIT_NOTE", "DEBIT_NOTE")) {
            for (ClientRecords.GstRegistration g : regList) {
                out.add(seriesDto(ensureSeries(clientId, g.getId(), kind), g.getId(), g.getGstin()));
            }
            if (regList.isEmpty()) out.add(seriesDto(ensureSeries(clientId, null, kind), null, null));
        }
        return out;
    }

    @Transactional
    public SeriesDto updateSeriesPrefix(UUID seriesId, String prefix) {
        String p = prefix == null ? "" : prefix.trim().toUpperCase();
        if (!p.matches("^[A-Z0-9]{1,4}$")) {
            throw ApiException.field("prefix", "Prefix must be 1-4 letters or digits so the number stays within 16 characters");
        }
        Map<String, Object> row = jdbc.queryForMap("select client_id, gst_registration_id from invoice_series where id = ?", seriesId);
        UUID clientId = (UUID) row.get("client_id");
        clients.findOpen(clientId);
        jdbc.update("update invoice_series set prefix = ? where id = ?", p, seriesId);
        audit.record("INVOICE_SERIES_UPDATED", "INVOICE_SERIES", seriesId, clientId, Map.of("prefix", p));
        UUID regId = (UUID) row.get("gst_registration_id");
        return seriesDto(seriesId, regId, regId == null ? null : gstRegs.findById(regId).map(ClientRecords.GstRegistration::getGstin).orElse(null));
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    public CreateResult create(InvoiceRequest r, String idempotencyKey) {
        CurrentUser me = CurrentUser.require();
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null) {
            if (!key.matches("^[A-Za-z0-9_-]{8,80}$")) {
                throw ApiException.badRequest("BAD_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-80 letters, digits, _ or -");
            }
            var existing = invoices.findByClientIdAndIdempotencyKey(r.clientId(), key);
            if (existing.isPresent()) return new CreateResult(toDto(existing.get(), true), false);
        }
        requireActiveClient(r.clientId());
        Invoice inv = new Invoice(r.clientId(), r.invoiceType(), me.id(), key);
        apply(inv, r);
        invoices.save(inv);
        audit.record("INVOICE_CREATED", "INVOICE", inv.getId(), inv.getClientId(), summary(inv));
        return new CreateResult(toDto(inv, true), true);
    }

    @Transactional
    public InvoiceDto update(UUID id, InvoiceRequest r) {
        Invoice inv = find(id);
        if (!inv.getClientId().equals(r.clientId()) || inv.getInvoiceType() != r.invoiceType()) {
            throw ApiException.notFound("Invoice");
        }
        if (inv.getStatus() != Invoice.Status.DRAFT) {
            throw ApiException.conflict("INVALID_TRANSITION", "Only draft invoices can be edited");
        }
        if (r.version() == null || r.version() != inv.getVersion()) {
            throw ApiException.conflict("STALE_VERSION", "This invoice was changed by someone else. Reload and try again.");
        }
        requireActiveClient(r.clientId());
        apply(inv, r);
        invoices.save(inv);
        audit.record("INVOICE_UPDATED", "INVOICE", id, inv.getClientId(), summary(inv));
        return toDto(inv, true);
    }

    @Transactional
    public InvoiceDto submit(UUID id) {
        CurrentUser me = CurrentUser.require();
        Invoice inv = find(id);
        if (inv.getStatus() != Invoice.Status.DRAFT) {
            throw ApiException.conflict("INVALID_TRANSITION", "Only draft invoices can be submitted");
        }
        requireActiveClient(inv.getClientId());
        requireOpenPeriod(inv);
        inv.submit(me.id());
        invoices.save(inv);
        audit.record("INVOICE_SUBMITTED", "INVOICE", id, inv.getClientId(), summary(inv));
        return toDto(inv, true);
    }

    @Transactional
    public InvoiceDto reject(UUID id, String reason) {
        Invoice inv = find(id);
        if (inv.getStatus() != Invoice.Status.PENDING_APPROVAL) {
            throw ApiException.conflict("INVALID_TRANSITION", "Only invoices awaiting approval can be rejected");
        }
        if (reason == null || reason.isBlank()) throw ApiException.field("reason", "Give a reason for rejecting the invoice");
        inv.reject(reason.trim());
        invoices.save(inv);
        Map<String, Object> d = summary(inv);
        d.put("reason", reason.trim());
        audit.record("INVOICE_REJECTED", "INVOICE", id, inv.getClientId(), d);
        return toDto(inv, true);
    }

    @Transactional
    public InvoiceDto cancel(UUID id) {
        Invoice inv = find(id);
        if (inv.getStatus() == Invoice.Status.POSTED || inv.getStatus() == Invoice.Status.CANCELLED) {
            throw ApiException.conflict("INVALID_TRANSITION", "Posted or cancelled invoices cannot be cancelled (use a credit note for posted invoices)");
        }
        inv.cancel();
        invoices.save(inv);
        audit.record("INVOICE_CANCELLED", "INVOICE", id, inv.getClientId(), summary(inv));
        return toDto(inv, true);
    }

    /** Approves and posts in one step: numbers a sales invoice and records the accounting entry. */
    @Transactional
    public InvoiceDto approveAndPost(UUID id) {
        CurrentUser me = CurrentUser.require();
        Invoice inv = find(id);
        if (inv.getStatus() == Invoice.Status.POSTED) return toDto(inv, true);
        if (inv.getStatus() != Invoice.Status.PENDING_APPROVAL) {
            throw ApiException.conflict("INVALID_TRANSITION", "Only invoices awaiting approval can be approved");
        }
        if (!me.has(JournalService.APPROVE)) throw ApiException.forbidden("FORBIDDEN", "Only an approver can approve invoices");
        boolean self = settings.checkApprover(me.id(), "approve", inv.getPreparedBy(), inv.getSubmittedBy()).selfApproval();
        requireActiveClient(inv.getClientId());
        AccountingPeriod period = requireOpenPeriod(inv);
        Party party = parties.findById(inv.getPartyId()).orElseThrow();
        Invoice original = null;
        if (inv.getInvoiceType().isNote()) {
            original = find(inv.getOriginalInvoiceId());
            BigDecimal balance = noteableBalance(original, inv.getId());
            if (inv.getGrandTotal().compareTo(balance) > 0) {
                throw ApiException.conflict("NOTE_EXCEEDS_INVOICE", "This note (₹" + Money.str(inv.getGrandTotal())
                        + ") exceeds the remaining value of the original invoice (₹" + Money.str(balance) + ")");
            }
        }
        String kind = switch (inv.getInvoiceType()) {
            case SALES -> "INVOICE";
            case CREDIT_NOTE -> "CREDIT_NOTE";
            case DEBIT_NOTE -> "DEBIT_NOTE";
            case PURCHASE -> null;
        };
        String number = kind == null ? null
                : nextInvoiceNumber(inv.getClientId(), inv.getGstRegistrationId(), period.getFinancialYear(), kind);
        String docRef = number != null ? number : inv.getSupplierInvoiceNumber();
        String originalRef = original == null ? null
                : (original.getInvoiceNumber() != null ? original.getInvoiceNumber() : original.getSupplierInvoiceNumber());
        String narration = switch (inv.getInvoiceType()) {
            case SALES -> "Sales invoice " + number + " to " + party.getName();
            case PURCHASE -> "Purchase invoice " + inv.getSupplierInvoiceNumber() + " from " + party.getName();
            case CREDIT_NOTE -> "Credit note " + number + " to " + party.getName() + " against " + originalRef
                    + (inv.getNoteReason() != null ? " (" + inv.getNoteReason() + ")" : "");
            case DEBIT_NOTE -> "Debit note " + number + " to " + party.getName() + " against " + originalRef
                    + (inv.getNoteReason() != null ? " (" + inv.getNoteReason() + ")" : "");
        } + (inv.isReverseCharge() ? " (reverse charge)" : "");
        JournalEntry journal = journals.createPosted(inv.getClientId(), inv.getInvoiceDate(), narration, docRef,
                postingLines(inv, party, docRef), inv.getPreparedBy(), inv.getSubmittedBy(), me.id(), "INVOICE");
        inv.post(me.id(), self, number, journal.getId());
        invoices.saveAndFlush(inv);
        Map<String, Object> d = summary(inv);
        d.put("journal", journal.getEntryNumber());
        if (self) d.put("selfApproved", true);
        audit.record("INVOICE_POSTED", "INVOICE", id, inv.getClientId(), d);
        return toDto(inv, true);
    }

    // ---------------------------------------------------------------- posting

    /**
     * Sales:    Dr Receivable (party) grand total; Cr income per line; Cr Output CGST/SGST/IGST.
     * Purchase: Dr expense/asset per line; Dr Input CGST/SGST/IGST (ITC, subject to eligibility review);
     *           Cr Payable (party). Reverse charge: tax credited to "GST payable under RCM" instead of the vendor.
     *           Without a GST registration of our own, tax is added to the cost (no ITC).
     */
    List<JournalEntry.Line> postingLines(Invoice inv, Party party, String docRef) {
        List<JournalEntry.Line> base = baseLines(inv, party, docRef);
        if (!inv.getInvoiceType().isNote()) return base;
        // A credit/debit note is the mirror image of the document type it adjusts.
        List<JournalEntry.Line> mirrored = new ArrayList<>();
        for (JournalEntry.Line l : base) {
            mirrored.add(new JournalEntry.Line(l.getAccountId(), l.getCredit(), l.getDebit(), l.getDescription(), l.getPartyId()));
        }
        return mirrored;
    }

    private List<JournalEntry.Line> baseLines(Invoice inv, Party party, String docRef) {
        UUID clientId = inv.getClientId();
        List<JournalEntry.Line> lines = new ArrayList<>();
        Map<UUID, BigDecimal> byAccount = new LinkedHashMap<>();
        boolean sales = inv.getInvoiceType().salesSide();
        boolean itc = !sales && inv.getGstRegistrationId() != null;
        for (Invoice.Line l : inv.getLines()) {
            BigDecimal amount = l.getTaxableValue();
            if (!sales && !itc) amount = amount.add(l.getCgst()).add(l.getSgst()).add(l.getIgst());
            byAccount.merge(l.getAccountId(), amount, BigDecimal::add);
        }
        String desc = docRef;
        if (sales) {
            lines.add(new JournalEntry.Line(account(clientId, "1130", "Trade Receivables"), inv.getGrandTotal(), Money.ZERO, desc, party.getId()));
            byAccount.forEach((acc, amt) -> lines.add(new JournalEntry.Line(acc, Money.ZERO, amt, desc)));
            addTax(lines, clientId, "2121", "Output CGST", inv.getCgstTotal(), false);
            addTax(lines, clientId, "2122", "Output SGST", inv.getSgstTotal(), false);
            addTax(lines, clientId, "2123", "Output IGST", inv.getIgstTotal(), false);
        } else {
            byAccount.forEach((acc, amt) -> lines.add(new JournalEntry.Line(acc, amt, Money.ZERO, desc)));
            BigDecimal tax = inv.getCgstTotal().add(inv.getSgstTotal()).add(inv.getIgstTotal());
            if (itc) {
                addTax(lines, clientId, "1141", "Input CGST", inv.getCgstTotal(), true);
                addTax(lines, clientId, "1142", "Input SGST", inv.getSgstTotal(), true);
                addTax(lines, clientId, "1143", "Input IGST", inv.getIgstTotal(), true);
            }
            BigDecimal payable = inv.isReverseCharge() && itc ? inv.getGrandTotal().subtract(tax) : inv.getGrandTotal();
            lines.add(new JournalEntry.Line(account(clientId, "2110", "Trade Payables"), Money.ZERO, payable, desc, party.getId()));
            if (inv.isReverseCharge() && itc && tax.signum() > 0) {
                lines.add(new JournalEntry.Line(account(clientId, "2125", "GST Payable under Reverse Charge"), Money.ZERO, tax, desc));
            }
        }
        return lines;
    }

    private void addTax(List<JournalEntry.Line> lines, UUID clientId, String code, String label, BigDecimal amount,
                        boolean debit) {
        if (amount.signum() == 0) return;
        UUID acc = account(clientId, code, label);
        lines.add(debit ? new JournalEntry.Line(acc, amount, Money.ZERO, label)
                : new JournalEntry.Line(acc, Money.ZERO, amount, label));
    }

    private UUID account(UUID clientId, String templateCode, String label) {
        return accounts.findByClientIdAndTemplateCode(clientId, TPL + templateCode)
                .filter(a -> a.isActive() && !a.isGroup())
                .map(LedgerAccount::getId)
                .orElseThrow(() -> ApiException.conflict("ACCOUNT_MISSING", "The '" + label
                        + "' ledger from the standard chart is missing or inactive for this client"));
    }

    // ---------------------------------------------------------------- validation & mapping

    private void apply(Invoice inv, InvoiceRequest r) {
        boolean note = r.invoiceType().isNote();
        Invoice original = null;
        if (note) {
            if (r.originalInvoiceId() == null) throw ApiException.field("originalInvoiceId", "Choose the invoice this note adjusts");
            original = invoices.findById(r.originalInvoiceId()).filter(o -> o.getClientId().equals(r.clientId()))
                    .orElseThrow(() -> ApiException.field("originalInvoiceId", "Original invoice not found"));
            Invoice.Type expected = r.invoiceType() == Invoice.Type.CREDIT_NOTE ? Invoice.Type.SALES : Invoice.Type.PURCHASE;
            if (original.getInvoiceType() != expected || original.getStatus() != Invoice.Status.POSTED) {
                throw ApiException.field("originalInvoiceId", r.invoiceType() == Invoice.Type.CREDIT_NOTE
                        ? "A credit note must adjust a posted sales invoice" : "A debit note must adjust a posted purchase invoice");
            }
            if (r.invoiceDate().isBefore(original.getInvoiceDate())) {
                throw ApiException.field("invoiceDate", "A note cannot be dated before the original invoice");
            }
            if (blank(r.noteReason()) == null) throw ApiException.field("noteReason", "Give the reason for the note (e.g. goods returned, rate difference)");
        }
        UUID partyId = note ? original.getPartyId() : r.partyId();
        Party party = parties.findById(partyId).filter(p -> p.getClientId().equals(r.clientId()))
                .orElseThrow(() -> ApiException.field("partyId", "Customer or vendor not found"));
        boolean sales = r.invoiceType().salesSide();
        if (!note && sales && !party.isCustomer()) throw ApiException.field("partyId", "Choose a customer for a sales invoice");
        if (!note && !sales && !party.isVendor()) throw ApiException.field("partyId", "Choose a vendor for a purchase invoice");
        if (!party.isActive()) throw ApiException.field("partyId", "This party is inactive");

        AccountingPeriod period = periods.periodFor(r.clientId(), r.invoiceDate(), "invoiceDate");
        if (period.getStatus() != AccountingPeriod.Status.OPEN) {
            throw ApiException.field("invoiceDate", "The accounting period for this date is closed");
        }

        ClientRecords.GstRegistration reg = null;
        UUID regId = note ? original.getGstRegistrationId() : r.gstRegistrationId();
        if (note && regId != null) {
            reg = gstRegs.findById(regId).orElseThrow();
        } else if (regId != null) {
            reg = gstRegs.findById(regId).filter(g -> g.getClientId().equals(r.clientId()))
                    .orElseThrow(() -> ApiException.field("gstRegistrationId", "GST registration not found"));
            if (!"ACTIVE".equals(reg.getStatus())) throw ApiException.field("gstRegistrationId", "This GST registration is not active");
        } else if (!note && sales && gstRegs.countActive(r.clientId()) > 0) {
            throw ApiException.field("gstRegistrationId", "Choose the GSTIN this invoice is issued from");
        }

        String pos = note ? original.getPlaceOfSupply() : blank(r.placeOfSupply());
        if (pos == null) pos = sales ? party.getStateCode() : (reg != null ? reg.getStateCode() : party.getStateCode());
        if (pos == null || !IndianStates.isValid(pos)) {
            throw ApiException.field("placeOfSupply", "Choose the place of supply (state)");
        }
        // Intra-state when supplier location and place of supply are in the same state, otherwise inter-state.
        String ourState = reg != null ? reg.getStateCode() : null;
        Invoice.SupplyType supply;
        if (note) {
            supply = original.getSupplyType();
        } else if (sales) {
            supply = ourState == null || ourState.equals(pos) ? Invoice.SupplyType.INTRA : Invoice.SupplyType.INTER;
        } else {
            String supplierState = party.getStateCode() != null ? party.getStateCode() : pos;
            supply = supplierState.equals(pos) ? Invoice.SupplyType.INTRA : Invoice.SupplyType.INTER;
        }

        boolean rcm = note ? original.isReverseCharge() : Boolean.TRUE.equals(r.reverseCharge());
        if (rcm && sales) throw ApiException.field("reverseCharge", "Reverse charge applies to purchase invoices here");

        String supplierNo = note ? null : blank(r.supplierInvoiceNumber());
        if (!sales && !note) {
            if (supplierNo == null) throw ApiException.field("supplierInvoiceNumber", "Enter the supplier's invoice number");
            if (!supplierNo.matches("^[A-Za-z0-9/-]{1,16}$")) {
                throw ApiException.field("supplierInvoiceNumber", "Up to 16 letters, digits, '-' or '/'");
            }
            if (invoices.purchaseNumberUsed(r.clientId(), party.getId(), supplierNo, inv.getId(),
                    yearStart(period), yearEnd(period))) {
                throw ApiException.conflict("DUPLICATE_INVOICE",
                        "Invoice " + supplierNo + " from this vendor is already recorded for this financial year");
            }
        }

        List<String> allowedRates = rates(r.invoiceDate()).stream().map(RateDto::rate).toList();
        List<InvoiceCalculator.LineResult> results = new ArrayList<>();
        List<Invoice.Line> lines = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            LineRequest lr = r.lines().get(i);
            String p = "lines[" + i + "]";
            BigDecimal qty = decimal(lr.quantity(), p + ".quantity", 3);
            if (qty.signum() <= 0) throw ApiException.field(p + ".quantity", "Quantity must be more than zero");
            BigDecimal price = com.taxpilot.accounting.Money.parse(lr.unitPrice(), p + ".unitPrice");
            BigDecimal rate = decimal(lr.gstRate(), p + ".gstRate", 2);
            if (allowedRates.stream().noneMatch(a -> new BigDecimal(a).compareTo(rate) == 0)) {
                throw ApiException.field(p + ".gstRate", "GST rate " + rate.stripTrailingZeros().toPlainString()
                        + "% is not valid on " + r.invoiceDate());
            }
            if (reg == null && sales && rate.signum() > 0) {
                throw ApiException.field(p + ".gstRate", "GST can only be charged from a GST registration");
            }
            final int lineNo = i + 1;
            LedgerAccount acc = accounts.findById(lr.accountId()).filter(a -> a.getClientId().equals(r.clientId()))
                    .orElseThrow(() -> ApiException.field(p + ".accountId", "Line " + lineNo + ": account not found"));
            if (acc.isGroup() || !acc.isActive()) {
                throw ApiException.field(p + ".accountId", "Line " + lineNo + ": choose an active ledger account");
            }
            if (sales && acc.getAccountType() != com.taxpilot.accounting.AccountType.INCOME) {
                throw ApiException.field(p + ".accountId", "Line " + lineNo + ": sales lines must use an income account");
            }
            if (!sales && acc.getAccountType() != com.taxpilot.accounting.AccountType.EXPENSE
                    && acc.getAccountType() != com.taxpilot.accounting.AccountType.ASSET) {
                throw ApiException.field(p + ".accountId", "Line " + lineNo + ": purchase lines must use an expense or asset account");
            }
            InvoiceCalculator.LineResult res = InvoiceCalculator.line(qty, price, rate, supply);
            results.add(res);
            lines.add(new Invoice.Line(lr.description().trim(), blank(lr.hsnSac()), acc.getId(), res));
        }
        InvoiceCalculator.Totals totals = InvoiceCalculator.totals(results);
        if (totals.grand().signum() <= 0) throw ApiException.field("lines", "The invoice total must be more than zero");

        LocalDate due = r.dueDate() != null ? r.dueDate() : r.invoiceDate().plusDays(party.getCreditDays());
        if (due.isBefore(r.invoiceDate())) throw ApiException.field("dueDate", "Due date cannot be before the invoice date");
        if (note && totals.grand().compareTo(noteableBalance(original, inv.getId())) > 0) {
            throw ApiException.field("lines", "The note total exceeds the remaining value of invoice "
                    + (original.getInvoiceNumber() != null ? original.getInvoiceNumber() : original.getSupplierInvoiceNumber())
                    + " (₹" + Money.str(noteableBalance(original, inv.getId())) + ")");
        }
        inv.setHeader(r.invoiceDate(), due, party.getId(), reg == null ? null : reg.getId(), pos, supply, rcm,
                sales || note ? null : supplierNo, blank(r.notes()));
        if (note) inv.setNote(original.getId(), blank(r.noteReason()));
        inv.replaceLines(lines, totals);
    }

    /** Original grand total less posted (and pending) notes against it, excluding the note being edited. */
    BigDecimal noteableBalance(Invoice original, UUID excludeNoteId) {
        BigDecimal used = jdbc.queryForObject("""
                select coalesce(sum(grand_total), 0) from invoices
                where original_invoice_id = ? and status in ('POSTED','PENDING_APPROVAL') and id <> ?
                """, BigDecimal.class, original.getId(), excludeNoteId);
        return original.getGrandTotal().subtract(Money.of(used));
    }

    private String nextInvoiceNumber(UUID clientId, UUID regId, String financialYear, String kind) {
        UUID seriesId = ensureSeries(clientId, regId, kind);
        String prefix = jdbc.queryForObject("select prefix from invoice_series where id = ?", String.class, seriesId);
        jdbc.update("""
                insert into invoice_sequences (series_id, financial_year, next_value) values (?, ?, 1)
                on conflict (series_id, financial_year) do nothing
                """, seriesId, financialYear);
        Integer n = jdbc.queryForObject("""
                update invoice_sequences set next_value = next_value + 1
                where series_id = ? and financial_year = ? returning next_value - 1
                """, Integer.class, seriesId, financialYear);
        return InvoiceCalculator.formatNumber(prefix, financialYear, n);
    }

    private UUID ensureSeries(UUID clientId, UUID regId, String kind) {
        String sql = "select id from invoice_series where client_id = ? and gst_registration_id is not distinct from ? and kind = ?";
        List<UUID> ids = jdbc.queryForList(sql, UUID.class, clientId, regId, kind);
        if (!ids.isEmpty()) return ids.getFirst();
        String prefix = switch (kind) {
            case "CREDIT_NOTE" -> "CN";
            case "DEBIT_NOTE" -> "DN";
            default -> "INV";
        };
        jdbc.update("insert into invoice_series (id, client_id, gst_registration_id, prefix, kind) values (?, ?, ?, ?, ?) "
                + "on conflict do nothing", UUID.randomUUID(), clientId, regId, prefix, kind);
        return jdbc.queryForObject(sql, UUID.class, clientId, regId, kind);
    }

    private SeriesDto seriesDto(UUID seriesId, UUID regId, String gstin) {
        String prefix = jdbc.queryForObject("select prefix from invoice_series where id = ?", String.class, seriesId);
        String kind = jdbc.queryForObject("select kind from invoice_series where id = ?", String.class, seriesId);
        LocalDate today = LocalDate.now();
        int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        String fy = startYear + "-" + String.format("%02d", (startYear + 1) % 100);
        List<Integer> next = jdbc.queryForList("select next_value from invoice_sequences where series_id = ? and financial_year = ?",
                Integer.class, seriesId, fy);
        return new SeriesDto(seriesId, kind, regId, gstin, prefix,
                InvoiceCalculator.formatNumber(prefix, fy, next.isEmpty() ? 1 : next.getFirst()));
    }

    private AccountingPeriod requireOpenPeriod(Invoice inv) {
        AccountingPeriod p = periods.periodFor(inv.getClientId(), inv.getInvoiceDate(), "invoiceDate");
        if (p.getStatus() != AccountingPeriod.Status.OPEN) {
            throw ApiException.conflict("PERIOD_CLOSED", "The accounting period for " + inv.getInvoiceDate() + " is closed");
        }
        return p;
    }

    private void requireActiveClient(UUID clientId) {
        if (clients.find(clientId).getStatus() != ClientStatus.ACTIVE) {
            throw ApiException.conflict("CLIENT_NOT_ACTIVE", "Invoices can only be recorded for active clients");
        }
    }

    private LocalDate yearStart(AccountingPeriod p) {
        return periods.list(p.getClientId()).stream().filter(x -> x.financialYear().equals(p.getFinancialYear()))
                .map(com.taxpilot.accounting.AccountingDtos.PeriodDto::startDate).min(LocalDate::compareTo)
                .orElse(p.getStartDate());
    }

    private LocalDate yearEnd(AccountingPeriod p) {
        return periods.list(p.getClientId()).stream().filter(x -> x.financialYear().equals(p.getFinancialYear()))
                .map(com.taxpilot.accounting.AccountingDtos.PeriodDto::endDate).max(LocalDate::compareTo)
                .orElse(p.getEndDate());
    }

    private Invoice find(UUID id) {
        return invoices.findById(id).orElseThrow(() -> ApiException.notFound("Invoice"));
    }

    private Map<String, Object> summary(Invoice i) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", i.getInvoiceType().name());
        if (i.getInvoiceNumber() != null) d.put("number", i.getInvoiceNumber());
        if (i.getSupplierInvoiceNumber() != null) d.put("supplierNumber", i.getSupplierInvoiceNumber());
        d.put("date", i.getInvoiceDate().toString());
        d.put("status", i.getStatus().name());
        d.put("grandTotal", Money.str(i.getGrandTotal()));
        return d;
    }

    private InvoiceDto toDto(Invoice i, boolean withLines) {
        Map<UUID, UserRef> cache = new HashMap<>();
        Party party = parties.findById(i.getPartyId()).orElse(null);
        String gstin = i.getGstRegistrationId() == null ? null
                : gstRegs.findById(i.getGstRegistrationId()).map(ClientRecords.GstRegistration::getGstin).orElse(null);
        List<LineDto> lines = !withLines ? List.of() : i.getLines().stream().map(l -> {
            LedgerAccount a = accounts.findById(l.getAccountId()).orElse(null);
            return new LineDto(l.getId(), l.getLineNo(), l.getDescription(), l.getHsnSac(), l.getAccountId(),
                    a == null ? null : a.getCode(), a == null ? null : a.getName(),
                    l.getQuantity().stripTrailingZeros().toPlainString(), Money.str(l.getUnitPrice()),
                    Money.str(l.getTaxableValue()), l.getGstRate().stripTrailingZeros().toPlainString(),
                    Money.str(l.getCgst()), Money.str(l.getSgst()), Money.str(l.getIgst()));
        }).toList();
        String journalNumber = i.getJournalEntryId() == null ? null : jdbc.queryForList(
                "select entry_number from journal_entries where id = ?", String.class, i.getJournalEntryId())
                .stream().findFirst().orElse(null);
        String originalNumber = i.getOriginalInvoiceId() == null ? null : invoices.findById(i.getOriginalInvoiceId())
                .map(o -> o.getInvoiceNumber() != null ? o.getInvoiceNumber() : o.getSupplierInvoiceNumber()).orElse(null);
        BigDecimal notesPosted = i.getInvoiceType().isNote() ? Money.ZERO : Money.of(jdbc.queryForObject(
                "select coalesce(sum(grand_total), 0) from invoices where original_invoice_id = ? and status = 'POSTED'",
                BigDecimal.class, i.getId()));
        BigDecimal noteable = i.getInvoiceType().isNote() || i.getStatus() != Invoice.Status.POSTED ? Money.ZERO
                : noteableBalance(i, i.getId());
        return new InvoiceDto(i.getId(), i.getClientId(), i.getInvoiceType(), i.getStatus(), i.getInvoiceNumber(),
                i.getSupplierInvoiceNumber(), i.getInvoiceDate(), i.getDueDate(), i.getPartyId(),
                party == null ? null : party.getName(), party == null ? null : party.getGstin(),
                i.getGstRegistrationId(), gstin, i.getPlaceOfSupply(), IndianStates.CODES.get(i.getPlaceOfSupply()),
                i.getSupplyType(), i.isReverseCharge(), Money.str(i.getTaxableTotal()), Money.str(i.getCgstTotal()),
                Money.str(i.getSgstTotal()), Money.str(i.getIgstTotal()), Money.str(i.getGrandTotal()), i.getNotes(),
                i.getJournalEntryId(), journalNumber, ref(i.getPreparedBy(), cache), ref(i.getSubmittedBy(), cache),
                i.getSubmittedAt(), ref(i.getApprovedBy(), cache), i.getApprovedAt(), i.isSelfApproved(),
                i.getRejectionReason(), lines, allowedActions(i, noteable), i.getCreatedAt(), i.getVersion(),
                i.getOriginalInvoiceId(), originalNumber, i.getNoteReason(), Money.str(notesPosted), Money.str(noteable));
    }

    private List<String> allowedActions(Invoice i, BigDecimal noteable) {
        CurrentUser me = CurrentUser.orNull();
        List<String> a = new ArrayList<>();
        if (me == null) return a;
        boolean write = me.has(JournalService.WRITE);
        boolean maker = me.id().equals(i.getPreparedBy()) || me.id().equals(i.getSubmittedBy());
        boolean checker = me.has(JournalService.APPROVE) && (!maker || settings.allowSelfApproval());
        switch (i.getStatus()) {
            case DRAFT -> {
                if (write) { a.add("EDIT"); a.add("SUBMIT"); a.add("CANCEL"); }
            }
            case PENDING_APPROVAL -> {
                if (checker) { a.add("APPROVE"); a.add("REJECT"); }
                if (write) a.add("CANCEL");
            }
            case POSTED -> {
                if (write && noteable.signum() > 0) {
                    if (i.getInvoiceType() == Invoice.Type.SALES) a.add("CREDIT_NOTE");
                    if (i.getInvoiceType() == Invoice.Type.PURCHASE) a.add("DEBIT_NOTE");
                }
            }
            default -> {
            }
        }
        return a;
    }

    private UserRef ref(UUID id, Map<UUID, UserRef> cache) {
        return id == null ? null : cache.computeIfAbsent(id, k -> UserRef.of(users.findById(k).orElse(null)));
    }

    private static BigDecimal decimal(String raw, String field, int scale) {
        try {
            BigDecimal v = new BigDecimal(raw.trim().replace(",", ""));
            if (v.signum() < 0) throw ApiException.field(field, "Cannot be negative");
            if (v.stripTrailingZeros().scale() > scale) throw ApiException.field(field, "At most " + scale + " decimal places");
            return v;
        } catch (NumberFormatException e) {
            throw ApiException.field(field, "Enter a valid number");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
