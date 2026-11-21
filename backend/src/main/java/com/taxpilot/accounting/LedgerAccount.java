package com.taxpilot.accounting;

import java.util.UUID;

import com.taxpilot.common.BaseEntity;
import com.taxpilot.common.Organisations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount extends BaseEntity {

    @Column(name = "organisation_id", nullable = false, updatable = false)
    private UUID organisationId = Organisations.DEFAULT_ID;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    private String subtype;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "is_group", nullable = false)
    private boolean group;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "template_code", updatable = false)
    private String templateCode;

    private String description;

    @Version
    private long version;

    protected LedgerAccount() {
    }

    public LedgerAccount(UUID clientId, String code, String name, AccountType type, boolean group, String templateCode) {
        this.clientId = clientId;
        this.code = code;
        this.name = name;
        this.accountType = type;
        this.group = group;
        this.templateCode = templateCode;
    }

    public UUID getClientId() { return clientId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public AccountType getAccountType() { return accountType; }
    public String getSubtype() { return subtype; }
    public UUID getParentId() { return parentId; }
    public boolean isGroup() { return group; }
    public boolean isActive() { return active; }
    public String getTemplateCode() { return templateCode; }
    public String getDescription() { return description; }
    public long getVersion() { return version; }

    public void setCode(String code) { this.code = code; }
    public void setName(String name) { this.name = name; }
    public void setAccountType(AccountType t) { this.accountType = t; }
    public void setSubtype(String subtype) { this.subtype = subtype; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }
    public void setGroup(boolean group) { this.group = group; }
    public void setActive(boolean active) { this.active = active; }
    public void setDescription(String description) { this.description = description; }
}
