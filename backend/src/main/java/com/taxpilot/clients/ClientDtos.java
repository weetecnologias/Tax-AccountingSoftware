package com.taxpilot.clients;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.taxpilot.users.UserDtos.UserRef;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ClientDtos {

    private ClientDtos() {
    }

    public record ClientRequest(
            @NotBlank @Size(max = 200) String displayName,
            @NotBlank @Size(max = 200) String legalName,
            @NotNull EntityType entityType,
            @NotBlank String pan,
            String tan,
            LocalDate dateOfIncorporation,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^$|^[+0-9 -]{7,20}$", message = "Enter a valid phone number") String phone,
            @Min(1) @Max(12) Integer fyStartMonth,
            Client.AccountingMethod accountingMethod,
            UUID accountantId,
            UUID reviewerId,
            @Size(max = 2000) String notes,
            Long version) {
    }

    public record StatusChangeRequest(@NotNull ClientStatus targetStatus, @Size(max = 1000) String comment,
                                      @Size(max = 1000) String closureReason) {
    }

    public record ClientSummary(UUID id, String clientCode, String displayName, String legalName,
                                EntityType entityType, String panMasked, ClientStatus status, UserRef accountant,
                                UserRef reviewer, Instant createdAt) {
    }

    public record ClientDetail(UUID id, String clientCode, String displayName, String legalName,
                               EntityType entityType, String pan, boolean panRevealed, String tan,
                               LocalDate dateOfIncorporation, String email, String phone, int fyStartMonth,
                               Client.AccountingMethod accountingMethod, ClientStatus status, UserRef accountant,
                               UserRef reviewer, String notes, String closureReason, Instant closedAt,
                               Instant createdAt, Instant updatedAt, long version, List<ClientStatus> allowedTransitions,
                               Counts counts) {
    }

    public record Counts(long gstRegistrations, long contacts, long documents, long documentsAwaitingReview) {
    }

    public record StatusHistoryEntry(UUID id, ClientStatus fromStatus, ClientStatus toStatus, UserRef changedBy,
                                     String comment, Instant changedAt) {
    }

    // ---- sub-records ----

    public record ContactRequest(@NotBlank @Size(max = 200) String name, @Size(max = 120) String designation,
                                 @Email @Size(max = 254) String email,
                                 @Pattern(regexp = "^$|^[+0-9 -]{7,20}$", message = "Enter a valid phone number")
                                 String phone,
                                 boolean primary) {
    }

    public record ContactDto(UUID id, String name, String designation, String email, String phone, boolean primary) {
        static ContactDto of(ClientRecords.Contact c) {
            return new ContactDto(c.getId(), c.getName(), c.getDesignation(), c.getEmail(), c.getPhone(),
                    c.isPrimary());
        }
    }

    public record AddressRequest(
            @NotBlank @Pattern(regexp = "^(REGISTERED|BUSINESS|BRANCH|COMMUNICATION|RESIDENTIAL)$",
                    message = "Unknown address type") String addressType,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @NotBlank String stateCode,
            @NotBlank String pincode) {
    }

    public record AddressDto(UUID id, String addressType, String line1, String line2, String city, String stateCode,
                             String stateName, String pincode) {
    }

    public record GstRegistrationRequest(
            @NotBlank String gstin,
            @Size(max = 200) String tradeName,
            @NotBlank @Pattern(regexp = "^(REGULAR|COMPOSITION|SEZ_UNIT|SEZ_DEVELOPER|CASUAL|NON_RESIDENT|ISD|TDS|TCS)$",
                    message = "Unknown registration type") String registrationType,
            @NotNull LocalDate effectiveDate,
            @NotBlank @Pattern(regexp = "^(MONTHLY|QUARTERLY)$", message = "Use MONTHLY or QUARTERLY")
            String filingFrequency,
            @Size(max = 500) String principalPlace,
            @Pattern(regexp = "^(ACTIVE|SUSPENDED|CANCELLED)?$", message = "Unknown status") String status,
            LocalDate cancelledOn) {
    }

    public record GstRegistrationDto(UUID id, String gstin, String tradeName, String stateCode, String stateName,
                                     String registrationType, LocalDate effectiveDate, String filingFrequency,
                                     String principalPlace, String status, LocalDate cancelledOn) {
    }

    public record BankAccountRequest(
            @NotBlank @Size(max = 120) String bankName,
            @Size(max = 120) String branch,
            @NotBlank @Size(max = 200) String accountHolder,
            String accountNumber,
            @NotBlank String ifsc,
            @NotBlank @Pattern(regexp = "^(SAVINGS|CURRENT|OD|CC|FD|OTHER)$", message = "Unknown account type")
            String accountType,
            Boolean active) {
    }

    public record BankAccountDto(UUID id, String bankName, String branch, String accountHolder, String accountNumber,
                                 boolean accountNumberRevealed, String ifsc, String accountType, boolean active) {
    }
}
