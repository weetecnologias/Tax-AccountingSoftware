package com.taxpilot.accounting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.taxpilot.users.UserDtos.UserRef;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Money is always transported as a decimal string (e.g. "1500.00") to avoid floating-point loss. */
public final class AccountingDtos {

    private AccountingDtos() {
    }

    // ---- chart of accounts ----

    public record AccountDto(UUID id, String code, String name, AccountType accountType, String subtype, UUID parentId,
                             boolean group, boolean active, String templateCode, String description, boolean used,
                             String cashFlowCategory, long version) {
    }

    public record AccountRequest(
            @NotNull UUID clientId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9.-]{1,20}$", message = "Use up to 20 letters, digits, . or -") String code,
            @NotBlank @Size(max = 150) String name,
            @NotNull AccountType accountType,
            @Size(max = 30) String subtype,
            UUID parentId,
            boolean group,
            Boolean active,
            @Size(max = 500) String description,
            @Pattern(regexp = "^$|^(CASH|OPERATING|NON_CASH|INVESTING|FINANCING)$", message = "Unknown cash-flow category")
            String cashFlowCategory,
            Long version) {
    }

    public record ClientRef(@NotNull UUID clientId) {
    }

    // ---- periods ----

    public record PeriodDto(UUID id, String financialYear, int periodNumber, LocalDate startDate, LocalDate endDate,
                            AccountingPeriod.Status status, UserRef closedBy, Instant closedAt, boolean yearClosed,
                            long version) {
    }

    public record OpenYearRequest(@NotNull UUID clientId,
                                  @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "Use the format 2025-26")
                                  String financialYear) {
    }

    public record ReasonRequest(@Size(max = 1000) String reason) {
    }

    // ---- journals ----

    public record JournalLineRequest(@NotNull UUID accountId, String debit, String credit,
                                     @Size(max = 500) String description, UUID partyId) {
        public JournalLineRequest(UUID accountId, String debit, String credit, String description) {
            this(accountId, debit, credit, description, null);
        }
    }

    public record JournalRequest(
            @NotNull UUID clientId,
            @NotNull LocalDate entryDate,
            JournalEntry.Type entryType,
            @NotBlank @Size(max = 1000) String narration,
            @Size(max = 100) String reference,
            @NotEmpty @Size(max = 500) List<@Valid JournalLineRequest> lines,
            Long version) {
    }

    public record JournalLineDto(UUID id, int lineNo, UUID accountId, String accountCode, String accountName,
                                 String debit, String credit, String description, UUID partyId, String partyName) {
    }

    public record JournalSummaryDto(UUID id, String entryNumber, LocalDate entryDate, JournalEntry.Type entryType,
                                    JournalEntry.Status status, String narration, String reference, String totalDebit,
                                    String totalCredit, boolean balanced, UserRef preparedBy, Instant createdAt,
                                    UUID reversalOfId, UUID reversedById) {
    }

    public record JournalDetailDto(UUID id, UUID clientId, String entryNumber, LocalDate entryDate,
                                   JournalEntry.Type entryType, JournalEntry.Status status, String narration,
                                   String reference, String totalDebit, String totalCredit, boolean balanced,
                                   String financialYear, AccountingPeriod.Status periodStatus,
                                   UserRef preparedBy, Instant createdAt, UserRef submittedBy, Instant submittedAt,
                                   UserRef approvedBy, Instant approvedAt, String approvalComment, UserRef postedBy,
                                   Instant postedAt, UserRef rejectedBy, Instant rejectedAt, String rejectionReason,
                                   UUID reversalOfId, String reversalOfNumber, UUID reversedById,
                                   String reversedByNumber, List<JournalLineDto> lines, List<String> allowedActions,
                                   long version) {
    }

    public record ReverseRequest(@NotNull LocalDate reversalDate, @NotBlank @Size(max = 1000) String reason) {
    }
}
