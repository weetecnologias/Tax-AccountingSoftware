package com.taxpilot.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.audit.AuditService;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.Organisations;
import com.taxpilot.common.validation.IndianIds;
import com.taxpilot.common.validation.IndianStates;
import com.taxpilot.security.CurrentUser;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Service
public class SettingsService {

    public interface OrganisationSettingsRepository extends JpaRepository<OrganisationSettings, UUID> {
    }

    public record SettingsRequest(
            @NotBlank @Size(max = 200) String displayName,
            @Size(max = 200) String legalName,
            @Size(max = 200) String tagline,
            @Size(max = 200) String addressLine1,
            @Size(max = 200) String addressLine2,
            @Size(max = 100) String city,
            String stateCode,
            String pincode,
            String gstin,
            String pan,
            @Email @Size(max = 254) String email,
            @Size(max = 20) String phone,
            @Size(max = 200) String website,
            @Size(max = 500) String reportFooter,
            boolean allowSelfApproval,
            long version) {
    }

    public record SettingsDto(String displayName, String legalName, String tagline, String addressLine1,
                              String addressLine2, String city, String stateCode, String stateName, String pincode,
                              String gstin, String pan, String email, String phone, String website,
                              String reportFooter, boolean allowSelfApproval, Instant updatedAt, long version) {
    }

    /** Letterhead block printed at the top of reports. */
    public record Letterhead(String name, String tagline, String address, String gstin, String pan, String contact,
                             String footer) {
    }

    /** Outcome of an approval-policy check, so callers can flag self-approvals in the audit trail. */
    public record ApprovalCheck(boolean selfApproval) {
    }

    private final OrganisationSettingsRepository repo;
    private final AuditService audit;

    public SettingsService(OrganisationSettingsRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public SettingsDto get() {
        return toDto(load());
    }

    @Transactional(readOnly = true)
    public boolean allowSelfApproval() {
        return load().isAllowSelfApproval();
    }

    /**
     * Enforces maker-checker unless the organisation allows self-approval.
     * Throws 403 MAKER_CHECKER when the approver is the preparer/submitter and self-approval is not allowed.
     */
    @Transactional(readOnly = true)
    public ApprovalCheck checkApprover(UUID approver, String verb, UUID... makers) {
        boolean isMaker = false;
        for (UUID m : makers) {
            if (approver.equals(m)) {
                isMaker = true;
                break;
            }
        }
        if (!isMaker) {
            return new ApprovalCheck(false);
        }
        if (!allowSelfApproval()) {
            throw ApiException.forbidden("MAKER_CHECKER",
                    "You prepared or submitted this item, so a different user must " + verb + " it");
        }
        return new ApprovalCheck(true);
    }

    @Transactional(readOnly = true)
    public Letterhead letterhead() {
        OrganisationSettings s = load();
        StringBuilder address = new StringBuilder();
        for (String part : new String[]{s.getAddressLine1(), s.getAddressLine2(), s.getCity(),
                s.getStateCode() == null ? null : IndianStates.CODES.get(s.getStateCode()), s.getPincode()}) {
            if (part != null && !part.isBlank()) {
                if (!address.isEmpty()) address.append(", ");
                address.append(part);
            }
        }
        String contact = String.join(" · ", java.util.stream.Stream.of(s.getPhone(), s.getEmail(), s.getWebsite())
                .filter(Objects::nonNull).toList());
        return new Letterhead(s.getLegalName() != null ? s.getLegalName() : s.getDisplayName(), s.getTagline(),
                address.isEmpty() ? null : address.toString(), s.getGstin(), s.getPan(),
                contact.isEmpty() ? null : contact, s.getReportFooter());
    }

    @Transactional
    public SettingsDto update(SettingsRequest r) {
        CurrentUser me = CurrentUser.require();
        OrganisationSettings s = load();
        if (s.getVersion() != r.version()) {
            throw ApiException.conflict("STALE_VERSION", "Settings were changed by someone else. Reload and try again.");
        }
        validate(r);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (s.isAllowSelfApproval() != r.allowSelfApproval()) changes.put("allowSelfApproval", r.allowSelfApproval());
        if (!s.getDisplayName().equals(r.displayName().trim())) changes.put("displayName", r.displayName().trim());
        changes.put("letterheadUpdated", true);
        s.apply(r, me.id());
        repo.save(s);
        audit.record(changes.containsKey("allowSelfApproval") ? "APPROVAL_POLICY_CHANGED" : "SETTINGS_UPDATED",
                "ORGANISATION", s.getOrganisationId(), null, changes);
        return toDto(s);
    }

    private static void validate(SettingsRequest r) {
        if (r.stateCode() != null && !r.stateCode().isBlank() && !IndianStates.isValid(r.stateCode())) {
            throw ApiException.field("stateCode", "Unknown state code");
        }
        if (r.pincode() != null && !r.pincode().isBlank() && !IndianIds.isValidPincode(r.pincode().trim())) {
            throw ApiException.field("pincode", "PIN code must be 6 digits and cannot start with 0");
        }
        if (r.gstin() != null && !r.gstin().isBlank() && !IndianIds.isValidGstin(IndianIds.normalise(r.gstin()))) {
            throw ApiException.field("gstin", "GSTIN is invalid (format, state code or check digit)");
        }
        if (r.pan() != null && !r.pan().isBlank() && !IndianIds.isValidPan(IndianIds.normalise(r.pan()))) {
            throw ApiException.field("pan", "PAN must be in the format AAAAA9999A");
        }
    }

    private OrganisationSettings load() {
        return repo.findById(Organisations.DEFAULT_ID).orElseThrow(() -> new IllegalStateException("Settings missing"));
    }

    private static SettingsDto toDto(OrganisationSettings s) {
        return new SettingsDto(s.getDisplayName(), s.getLegalName(), s.getTagline(), s.getAddressLine1(),
                s.getAddressLine2(), s.getCity(), s.getStateCode(),
                s.getStateCode() == null ? null : IndianStates.CODES.get(s.getStateCode()), s.getPincode(),
                s.getGstin(), s.getPan(), s.getEmail(), s.getPhone(), s.getWebsite(), s.getReportFooter(),
                s.isAllowSelfApproval(), s.getUpdatedAt(), s.getVersion());
    }
}
