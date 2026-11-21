package com.taxpilot.documents;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.taxpilot.common.Organisations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "documents")
public class Document {

    public enum Status {
        UPLOADED, DUPLICATE_SUSPECTED, REVIEW_REQUIRED, APPROVED, REJECTED, ARCHIVED;

        public static final Set<Status> AWAITING_REVIEW = EnumSet.of(UPLOADED, DUPLICATE_SUSPECTED, REVIEW_REQUIRED);
    }

    public enum ScanStatus { CLEAN, NOT_SCANNED }

    @Id
    private UUID id;

    @Column(name = "organisation_id", nullable = false, updatable = false)
    private UUID organisationId = Organisations.DEFAULT_ID;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType;

    @Column(name = "original_filename", nullable = false, updatable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false)
    private String sha256;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "financial_year")
    private String financialYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, updatable = false)
    private ScanStatus scanStatus;

    @Column(name = "duplicate_of_id", updatable = false)
    private UUID duplicateOfId;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_comment")
    private String reviewComment;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Document() {
    }

    public Document(UUID id, UUID clientId, DocumentType type, String originalFilename, String contentType,
                    long sizeBytes, String sha256, String storageKey, String financialYear, ScanStatus scanStatus,
                    UUID duplicateOfId, UUID uploadedBy) {
        this.id = id;
        this.clientId = clientId;
        this.documentType = type;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.financialYear = financialYear;
        this.scanStatus = scanStatus;
        this.duplicateOfId = duplicateOfId;
        this.status = duplicateOfId == null ? Status.UPLOADED : Status.DUPLICATE_SUSPECTED;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = Instant.now();
        this.updatedAt = this.uploadedAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void review(Status outcome, UUID reviewer, String comment) {
        this.status = outcome;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewComment = comment;
    }

    public void archive() {
        this.status = Status.ARCHIVED;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public DocumentType getDocumentType() { return documentType; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getStorageKey() { return storageKey; }
    public String getFinancialYear() { return financialYear; }
    public Status getStatus() { return status; }
    public ScanStatus getScanStatus() { return scanStatus; }
    public UUID getDuplicateOfId() { return duplicateOfId; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewComment() { return reviewComment; }
    public long getVersion() { return version; }
}
