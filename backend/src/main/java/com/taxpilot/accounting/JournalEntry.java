package com.taxpilot.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.taxpilot.common.BaseEntity;
import com.taxpilot.common.Organisations;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "journal_entries")
public class JournalEntry extends BaseEntity {

    public enum Status { DRAFT, PENDING_APPROVAL, APPROVED, POSTED, CANCELLED }

    public enum Type { GENERAL, OPENING, ADJUSTMENT, REVERSAL }

    @Column(name = "organisation_id", nullable = false, updatable = false)
    private UUID organisationId = Organisations.DEFAULT_ID;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(name = "entry_number")
    private String entryNumber;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "period_id", nullable = false)
    private UUID periodId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private Type entryType = Type.GENERAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    private String narration;

    private String reference;

    @Column(name = "total_debit", nullable = false)
    private BigDecimal totalDebit = Money.ZERO;

    @Column(name = "total_credit", nullable = false)
    private BigDecimal totalCredit = Money.ZERO;

    @Column(name = "prepared_by", nullable = false, updatable = false)
    private UUID preparedBy;

    @Column(name = "submitted_by") private UUID submittedBy;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "approved_by") private UUID approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "approval_comment") private String approvalComment;
    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "posted_at") private Instant postedAt;
    @Column(name = "rejected_by") private UUID rejectedBy;
    @Column(name = "rejected_at") private Instant rejectedAt;
    @Column(name = "rejection_reason") private String rejectionReason;

    @Column(name = "reversal_of_id", updatable = false)
    private UUID reversalOfId;

    @Column(name = "reversed_by_id")
    private UUID reversedById;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Version
    private long version;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<Line> lines = new ArrayList<>();

    protected JournalEntry() {
    }

    public JournalEntry(UUID clientId, UUID preparedBy, Type type, UUID reversalOfId, String idempotencyKey) {
        this.clientId = clientId;
        this.preparedBy = preparedBy;
        this.entryType = type;
        this.reversalOfId = reversalOfId;
        this.idempotencyKey = idempotencyKey;
    }

    // ---------------------------------------------------------------- behaviour

    public void replaceLines(List<Line> newLines) {
        lines.clear();
        int n = 1;
        BigDecimal dr = Money.ZERO;
        BigDecimal cr = Money.ZERO;
        for (Line l : newLines) {
            l.entry = this;
            l.lineNo = (short) n++;
            lines.add(l);
            dr = dr.add(l.debit);
            cr = cr.add(l.credit);
        }
        totalDebit = dr;
        totalCredit = cr;
    }

    public boolean isBalanced() {
        return lines.size() >= 2 && totalDebit.signum() > 0 && totalDebit.compareTo(totalCredit) == 0;
    }

    public void submit(UUID by) {
        status = Status.PENDING_APPROVAL;
        submittedBy = by;
        submittedAt = Instant.now();
        rejectedBy = null;
        rejectedAt = null;
        rejectionReason = null;
    }

    public void approve(UUID by, String comment) {
        status = Status.APPROVED;
        approvedBy = by;
        approvedAt = Instant.now();
        approvalComment = comment;
    }

    public void reject(UUID by, String reason) {
        status = Status.DRAFT;
        rejectedBy = by;
        rejectedAt = Instant.now();
        rejectionReason = reason;
        submittedBy = null;
        submittedAt = null;
    }

    public void post(UUID by, String number) {
        status = Status.POSTED;
        postedBy = by;
        postedAt = Instant.now();
        entryNumber = number;
    }

    public void cancel() {
        status = Status.CANCELLED;
    }

    public void markReversedBy(UUID reversalId) {
        reversedById = reversalId;
    }

    // ---------------------------------------------------------------- accessors

    public UUID getClientId() { return clientId; }
    public String getEntryNumber() { return entryNumber; }
    public LocalDate getEntryDate() { return entryDate; }
    public UUID getPeriodId() { return periodId; }
    public Type getEntryType() { return entryType; }
    public Status getStatus() { return status; }
    public String getNarration() { return narration; }
    public String getReference() { return reference; }
    public BigDecimal getTotalDebit() { return totalDebit; }
    public BigDecimal getTotalCredit() { return totalCredit; }
    public UUID getPreparedBy() { return preparedBy; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getApprovalComment() { return approvalComment; }
    public UUID getPostedBy() { return postedBy; }
    public Instant getPostedAt() { return postedAt; }
    public UUID getRejectedBy() { return rejectedBy; }
    public Instant getRejectedAt() { return rejectedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public UUID getReversalOfId() { return reversalOfId; }
    public UUID getReversedById() { return reversedById; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getVersion() { return version; }
    public List<Line> getLines() { return lines; }

    public void setEntryDate(LocalDate d, UUID periodId) {
        this.entryDate = d;
        this.periodId = periodId;
    }

    public void setNarration(String narration) { this.narration = narration; }
    public void setReference(String reference) { this.reference = reference; }

    @Entity(name = "JournalLine")
    @Table(name = "journal_entry_lines")
    public static class Line {

        @Id
        private UUID id = UUID.randomUUID();

        @ManyToOne(optional = false)
        @JoinColumn(name = "journal_entry_id", nullable = false, updatable = false)
        private JournalEntry entry;

        @Column(name = "line_no", nullable = false)
        private short lineNo;

        @Column(name = "account_id", nullable = false)
        private UUID accountId;

        @Column(nullable = false)
        private BigDecimal debit = Money.ZERO;

        @Column(nullable = false)
        private BigDecimal credit = Money.ZERO;

        private String description;

        protected Line() {
        }

        public Line(UUID accountId, BigDecimal debit, BigDecimal credit, String description) {
            this.accountId = accountId;
            this.debit = Money.of(debit);
            this.credit = Money.of(credit);
            this.description = description;
        }

        public UUID getId() { return id; }
        public short getLineNo() { return lineNo; }
        public UUID getAccountId() { return accountId; }
        public BigDecimal getDebit() { return debit; }
        public BigDecimal getCredit() { return credit; }
        public String getDescription() { return description; }
    }
}
