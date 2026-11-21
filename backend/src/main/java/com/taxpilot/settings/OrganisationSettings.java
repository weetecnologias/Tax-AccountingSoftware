package com.taxpilot.settings;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "organisation_settings")
public class OrganisationSettings {

    @Id
    @Column(name = "organisation_id")
    private UUID organisationId;

    @Column(name = "display_name", nullable = false) private String displayName;
    @Column(name = "legal_name") private String legalName;
    private String tagline;
    @Column(name = "address_line1") private String addressLine1;
    @Column(name = "address_line2") private String addressLine2;
    private String city;
    @Column(name = "state_code") private String stateCode;
    private String pincode;
    private String gstin;
    private String pan;
    private String email;
    private String phone;
    private String website;
    @Column(name = "report_footer") private String reportFooter;
    @Column(name = "allow_self_approval", nullable = false) private boolean allowSelfApproval;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "updated_by") private UUID updatedBy;
    @Version private long version;

    protected OrganisationSettings() {
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getOrganisationId() { return organisationId; }
    public String getDisplayName() { return displayName; }
    public String getLegalName() { return legalName; }
    public String getTagline() { return tagline; }
    public String getAddressLine1() { return addressLine1; }
    public String getAddressLine2() { return addressLine2; }
    public String getCity() { return city; }
    public String getStateCode() { return stateCode; }
    public String getPincode() { return pincode; }
    public String getGstin() { return gstin; }
    public String getPan() { return pan; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getWebsite() { return website; }
    public String getReportFooter() { return reportFooter; }
    public boolean isAllowSelfApproval() { return allowSelfApproval; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public long getVersion() { return version; }

    void apply(SettingsService.SettingsRequest r, UUID by) {
        displayName = r.displayName().trim();
        legalName = blank(r.legalName());
        tagline = blank(r.tagline());
        addressLine1 = blank(r.addressLine1());
        addressLine2 = blank(r.addressLine2());
        city = blank(r.city());
        stateCode = blank(r.stateCode());
        pincode = blank(r.pincode());
        gstin = blank(r.gstin()) == null ? null : r.gstin().trim().toUpperCase();
        pan = blank(r.pan()) == null ? null : r.pan().trim().toUpperCase();
        email = blank(r.email());
        phone = blank(r.phone());
        website = blank(r.website());
        reportFooter = blank(r.reportFooter());
        allowSelfApproval = r.allowSelfApproval();
        updatedBy = by;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
