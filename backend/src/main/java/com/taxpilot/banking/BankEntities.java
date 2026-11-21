package com.taxpilot.banking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Persistence for bank statement imports and their transactions. */
public final class BankEntities {

    private BankEntities() {
    }

    @Entity(name = "BankStatementImport")
    @Table(name = "bank_statement_imports")
    public static class StatementImport {
        public enum Status { IN_PROGRESS, RECONCILED }

        @Id private UUID id = UUID.randomUUID();
        @Column(name = "client_id", nullable = false, updatable = false) private UUID clientId;
        @Column(name = "ledger_account_id", nullable = false, updatable = false) private UUID ledgerAccountId;
        @Column(name = "bank_format", nullable = false, updatable = false) private String bankFormat;
        @Column(name = "original_filename", nullable = false, updatable = false) private String originalFilename;
        @Column(nullable = false, updatable = false) private String sha256;
        @Column(name = "period_from", nullable = false) private LocalDate periodFrom;
        @Column(name = "period_to", nullable = false) private LocalDate periodTo;
        @Column(name = "opening_balance") private BigDecimal openingBalance;
        @Column(name = "closing_balance") private BigDecimal closingBalance;
        @Column(name = "total_deposits", nullable = false) private BigDecimal totalDeposits;
        @Column(name = "total_withdrawals", nullable = false) private BigDecimal totalWithdrawals;
        @Column(name = "line_count", nullable = false) private int lineCount;
        @Column(name = "duplicate_count", nullable = false) private int duplicateCount;
        @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.IN_PROGRESS;
        @Column(name = "imported_by", nullable = false, updatable = false) private UUID importedBy;
        @Column(name = "imported_at", nullable = false, updatable = false) private Instant importedAt = Instant.now();
        @Column(name = "approved_by") private UUID approvedBy;
        @Column(name = "approved_at") private Instant approvedAt;
        @Column(name = "approval_comment") private String approvalComment;
        @Column(name = "self_approved", nullable = false) private boolean selfApproved;
        @Version private long version;

        protected StatementImport() {
        }

        StatementImport(UUID clientId, UUID ledgerAccountId, String format, String filename, String sha256,
                        UUID importedBy) {
            this.clientId = clientId;
            this.ledgerAccountId = ledgerAccountId;
            this.bankFormat = format;
            this.originalFilename = filename;
            this.sha256 = sha256;
            this.importedBy = importedBy;
        }

        void setSummary(LocalDate from, LocalDate to, BigDecimal opening, BigDecimal closing, BigDecimal deposits,
                        BigDecimal withdrawals, int lines, int duplicates) {
            this.periodFrom = from;
            this.periodTo = to;
            this.openingBalance = opening;
            this.closingBalance = closing;
            this.totalDeposits = deposits;
            this.totalWithdrawals = withdrawals;
            this.lineCount = lines;
            this.duplicateCount = duplicates;
        }

        void reconcile(UUID by, boolean self, String comment) {
            status = Status.RECONCILED;
            approvedBy = by;
            approvedAt = Instant.now();
            selfApproved = self;
            approvalComment = comment;
        }

        public UUID getId() { return id; }
        public UUID getClientId() { return clientId; }
        public UUID getLedgerAccountId() { return ledgerAccountId; }
        public String getBankFormat() { return bankFormat; }
        public String getOriginalFilename() { return originalFilename; }
        public LocalDate getPeriodFrom() { return periodFrom; }
        public LocalDate getPeriodTo() { return periodTo; }
        public BigDecimal getOpeningBalance() { return openingBalance; }
        public BigDecimal getClosingBalance() { return closingBalance; }
        public BigDecimal getTotalDeposits() { return totalDeposits; }
        public BigDecimal getTotalWithdrawals() { return totalWithdrawals; }
        public int getLineCount() { return lineCount; }
        public int getDuplicateCount() { return duplicateCount; }
        public Status getStatus() { return status; }
        public UUID getImportedBy() { return importedBy; }
        public Instant getImportedAt() { return importedAt; }
        public UUID getApprovedBy() { return approvedBy; }
        public Instant getApprovedAt() { return approvedAt; }
        public String getApprovalComment() { return approvalComment; }
        public boolean isSelfApproved() { return selfApproved; }
    }

    @Entity(name = "BankTransaction")
    @Table(name = "bank_transactions")
    public static class Transaction {
        public enum Status { UNMATCHED, MATCHED, EXCLUDED }

        @Id private UUID id = UUID.randomUUID();
        @Column(name = "import_id", nullable = false, updatable = false) private UUID importId;
        @Column(name = "client_id", nullable = false, updatable = false) private UUID clientId;
        @Column(name = "ledger_account_id", nullable = false, updatable = false) private UUID ledgerAccountId;
        @Column(name = "row_no", nullable = false, updatable = false) private int rowNo;
        @Column(name = "txn_date", nullable = false, updatable = false) private LocalDate txnDate;
        @Column(name = "value_date", updatable = false) private LocalDate valueDate;
        @Column(nullable = false, updatable = false) private String description;
        @Column(updatable = false) private String reference;
        @Column(nullable = false, updatable = false) private BigDecimal withdrawal;
        @Column(nullable = false, updatable = false) private BigDecimal deposit;
        @Column(updatable = false) private BigDecimal balance;
        @Column(nullable = false, updatable = false) private String fingerprint;
        @Column(name = "category_hint", updatable = false) private String categoryHint;
        @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.UNMATCHED;
        @Column(name = "match_type") private String matchType;
        @Column(name = "match_confidence") private Short matchConfidence;
        @Column(name = "matched_line_id") private UUID matchedLineId;
        @Column(name = "matched_by") private UUID matchedBy;
        @Column(name = "matched_at") private Instant matchedAt;
        @Column(name = "exclusion_reason") private String exclusionReason;
        @Version private long version;

        protected Transaction() {
        }

        Transaction(UUID importId, UUID clientId, UUID ledgerAccountId, BankStatementParser.Row r, String fingerprint,
                    String categoryHint) {
            this.importId = importId;
            this.clientId = clientId;
            this.ledgerAccountId = ledgerAccountId;
            this.rowNo = r.rowNo();
            this.txnDate = r.date();
            this.valueDate = r.valueDate();
            this.description = r.description();
            this.reference = r.reference();
            this.withdrawal = r.withdrawal();
            this.deposit = r.deposit();
            this.balance = r.balance();
            this.fingerprint = fingerprint;
            this.categoryHint = categoryHint;
        }

        public BigDecimal amount() {
            return deposit.signum() > 0 ? deposit : withdrawal;
        }

        public boolean isDeposit() {
            return deposit.signum() > 0;
        }

        void match(UUID lineId, String type, int confidence, UUID by) {
            status = Status.MATCHED;
            matchedLineId = lineId;
            matchType = type;
            matchConfidence = (short) confidence;
            matchedBy = by;
            matchedAt = Instant.now();
            exclusionReason = null;
        }

        void unmatch() {
            status = Status.UNMATCHED;
            matchedLineId = null;
            matchType = null;
            matchConfidence = null;
            matchedBy = null;
            matchedAt = null;
            exclusionReason = null;
        }

        void exclude(String reason, UUID by) {
            status = Status.EXCLUDED;
            exclusionReason = reason;
            matchedBy = by;
            matchedAt = Instant.now();
        }

        public UUID getId() { return id; }
        public UUID getImportId() { return importId; }
        public UUID getClientId() { return clientId; }
        public UUID getLedgerAccountId() { return ledgerAccountId; }
        public int getRowNo() { return rowNo; }
        public LocalDate getTxnDate() { return txnDate; }
        public LocalDate getValueDate() { return valueDate; }
        public String getDescription() { return description; }
        public String getReference() { return reference; }
        public BigDecimal getWithdrawal() { return withdrawal; }
        public BigDecimal getDeposit() { return deposit; }
        public BigDecimal getBalance() { return balance; }
        public String getCategoryHint() { return categoryHint; }
        public Status getStatus() { return status; }
        public String getMatchType() { return matchType; }
        public Short getMatchConfidence() { return matchConfidence; }
        public UUID getMatchedLineId() { return matchedLineId; }
        public UUID getMatchedBy() { return matchedBy; }
        public Instant getMatchedAt() { return matchedAt; }
        public String getExclusionReason() { return exclusionReason; }
        public long getVersion() { return version; }
    }

    public interface ImportRepository extends JpaRepository<StatementImport, UUID> {
        List<StatementImport> findByClientIdOrderByImportedAtDesc(UUID clientId);

        Optional<StatementImport> findByLedgerAccountIdAndSha256(UUID ledgerAccountId, String sha256);

        long countByStatus(StatementImport.Status status);
    }

    public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
        List<Transaction> findByImportIdOrderByRowNoAsc(UUID importId);

        boolean existsByLedgerAccountIdAndFingerprint(UUID ledgerAccountId, String fingerprint);

        long countByImportIdAndStatus(UUID importId, Transaction.Status status);

        Optional<Transaction> findByMatchedLineId(UUID lineId);

        @Query("select t.matchedLineId from BankTransaction t where t.ledgerAccountId = :acc and t.matchedLineId is not null")
        List<UUID> matchedLineIds(@Param("acc") UUID ledgerAccountId);
    }
}
