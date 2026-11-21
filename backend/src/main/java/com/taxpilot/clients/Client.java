package com.taxpilot.clients;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.taxpilot.common.BaseEntity;
import com.taxpilot.common.Organisations;
import com.taxpilot.users.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "clients")
public class Client extends BaseEntity {

    public enum AccountingMethod { ACCRUAL, CASH }

    @Column(name = "organisation_id", nullable = false, updatable = false)
    private UUID organisationId = Organisations.DEFAULT_ID;

    @Column(name = "client_code", nullable = false, updatable = false)
    private String clientCode;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private EntityType entityType;

    @Column(name = "pan_encrypted", nullable = false)
    private byte[] panEncrypted;

    @Column(name = "pan_hash", nullable = false)
    private String panHash;

    @Column(name = "pan_last4", nullable = false)
    private String panLast4;

    private String tan;

    @Column(name = "date_of_incorporation")
    private LocalDate dateOfIncorporation;

    private String email;
    private String phone;

    @Column(name = "fy_start_month", nullable = false)
    private short fyStartMonth = 4;

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_method", nullable = false)
    private AccountingMethod accountingMethod = AccountingMethod.ACCRUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClientStatus status = ClientStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accountant_id")
    private User accountant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    private User reviewer;

    private String notes;

    @Column(name = "closure_reason")
    private String closureReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Version
    private long version;

    protected Client() {
    }

    public Client(String clientCode, UUID createdBy) {
        this.clientCode = clientCode;
        this.createdBy = createdBy;
    }

    public void setPan(byte[] encrypted, String hash, String last4) {
        this.panEncrypted = encrypted;
        this.panHash = hash;
        this.panLast4 = last4;
    }

    public void close(String reason) {
        this.status = ClientStatus.CLOSED;
        this.closureReason = reason;
        this.closedAt = Instant.now();
    }

    public UUID getOrganisationId() { return organisationId; }
    public String getClientCode() { return clientCode; }
    public String getDisplayName() { return displayName; }
    public String getLegalName() { return legalName; }
    public EntityType getEntityType() { return entityType; }
    public byte[] getPanEncrypted() { return panEncrypted; }
    public String getPanHash() { return panHash; }
    public String getPanLast4() { return panLast4; }
    public String getTan() { return tan; }
    public LocalDate getDateOfIncorporation() { return dateOfIncorporation; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public short getFyStartMonth() { return fyStartMonth; }
    public AccountingMethod getAccountingMethod() { return accountingMethod; }
    public ClientStatus getStatus() { return status; }
    public User getAccountant() { return accountant; }
    public User getReviewer() { return reviewer; }
    public String getNotes() { return notes; }
    public String getClosureReason() { return closureReason; }
    public Instant getClosedAt() { return closedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public long getVersion() { return version; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public void setEntityType(EntityType entityType) { this.entityType = entityType; }
    public void setTan(String tan) { this.tan = tan; }
    public void setDateOfIncorporation(LocalDate d) { this.dateOfIncorporation = d; }
    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setFyStartMonth(short m) { this.fyStartMonth = m; }
    public void setAccountingMethod(AccountingMethod m) { this.accountingMethod = m; }
    public void setStatus(ClientStatus status) { this.status = status; }
    public void setAccountant(User accountant) { this.accountant = accountant; }
    public void setReviewer(User reviewer) { this.reviewer = reviewer; }
    public void setNotes(String notes) { this.notes = notes; }
}
