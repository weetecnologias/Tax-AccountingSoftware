package com.taxpilot.documents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.Client;
import com.taxpilot.clients.ClientRepositories.ClientRepository;
import com.taxpilot.clients.ClientService;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.PageResponse;
import com.taxpilot.common.crypto.BlindIndex;
import com.taxpilot.common.crypto.FieldEncryptor;
import com.taxpilot.config.TaxpilotProperties;
import com.taxpilot.documents.scan.MalwareScanner;
import com.taxpilot.documents.storage.ObjectStorage;
import com.taxpilot.security.CurrentUser;
import com.taxpilot.users.UserDtos.UserRef;
import com.taxpilot.users.UserRepository;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final Pattern FINANCIAL_YEAR = Pattern.compile("^(\\d{4})-(\\d{2})$");

    private final DocumentRepository documents;
    private final ClientService clients;
    private final ClientRepository clientRepository;
    private final UserRepository users;
    private final ObjectStorage storage;
    private final MalwareScanner scanner;
    private final FieldEncryptor encryptor;
    private final AuditService audit;
    private final long maxBytes;

    public DocumentService(DocumentRepository documents, ClientService clients, ClientRepository clientRepository,
                           UserRepository users, ObjectStorage storage, MalwareScanner scanner,
                           FieldEncryptor encryptor, AuditService audit, TaxpilotProperties props) {
        this.documents = documents;
        this.clients = clients;
        this.clientRepository = clientRepository;
        this.users = users;
        this.storage = storage;
        this.scanner = scanner;
        this.encryptor = encryptor;
        this.audit = audit;
        this.maxBytes = props.storage().maxUploadBytes();
    }

    public record DocumentDto(UUID id, UUID clientId, String clientCode, String clientName, DocumentType documentType,
                              String originalFilename, String contentType, long sizeBytes, String sha256,
                              String financialYear, Document.Status status, Document.ScanStatus scanStatus,
                              UUID duplicateOfId, UserRef uploadedBy, Instant uploadedAt, UserRef reviewedBy,
                              Instant reviewedAt, String reviewComment, long version, boolean canReview) {
    }

    public record DownloadedFile(String filename, String contentType, byte[] data) {
    }

    @Transactional
    public DocumentDto upload(UUID clientId, DocumentType type, String financialYear, MultipartFile file) {
        CurrentUser me = CurrentUser.require();
        Client client = clients.findOpen(clientId);
        if (type == null) {
            throw ApiException.field("documentType", "Choose a document type");
        }
        String fy = validateFinancialYear(financialYear);
        if (file == null || file.isEmpty()) {
            throw ApiException.field("file", "Choose a file to upload");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "The file exceeds the " + (maxBytes / (1024 * 1024)) + " MB limit");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String filename = FileTypeDetector.sanitiseFilename(file.getOriginalFilename());
        String contentType = FileTypeDetector.detect(filename, data).orElseThrow(() -> ApiException.field("file",
                "Unsupported or mismatched file type. Allowed: PDF, PNG, JPG, XLSX, XLS, CSV"));

        MalwareScanner.Result scan;
        try {
            scan = scanner.scan(data);
        } catch (MalwareScanner.ScannerUnavailableException e) {
            log.error("Malware scanner unavailable; upload refused", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SCANNER_UNAVAILABLE",
                    "The malware scanner is unavailable, so the file was not accepted. Try again later.");
        }
        String sha256 = BlindIndex.sha256Hex(data);
        if (scan.verdict() == MalwareScanner.Verdict.INFECTED) {
            audit.recordIndependently("DOCUMENT_INFECTED_REJECTED", "DOCUMENT", null, clientId,
                    Map.of("filename", filename, "sha256", sha256, "signature", String.valueOf(scan.signature())));
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "MALWARE_DETECTED",
                    "The file failed the malware scan and was rejected");
        }

        UUID duplicateOf = documents.findFirstByClientIdAndSha256OrderByUploadedAtAsc(clientId, sha256)
                .map(Document::getId).orElse(null);
        UUID id = UUID.randomUUID();
        String key = "documents/" + clientId + "/" + id;
        Document.ScanStatus scanStatus = scan.verdict() == MalwareScanner.Verdict.CLEAN
                ? Document.ScanStatus.CLEAN : Document.ScanStatus.NOT_SCANNED;
        Document doc = new Document(id, clientId, type, filename, contentType, data.length, sha256, key, fy,
                scanStatus, duplicateOf, me.id());

        storage.put(key, encryptor.encrypt(data));
        try {
            documents.saveAndFlush(doc);
        } catch (RuntimeException e) {
            safeDelete(key);
            throw e;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("filename", filename);
        details.put("type", type.name());
        details.put("sizeBytes", data.length);
        details.put("scan", scanStatus.name());
        if (duplicateOf != null) details.put("duplicateOf", duplicateOf.toString());
        audit.record("DOCUMENT_UPLOADED", "DOCUMENT", id, clientId, details);
        return toDto(doc, client);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentDto> list(UUID clientId, Document.Status status, DocumentType type, String q,
                                          Pageable pageable) {
        Specification<Document> spec = (r, query, cb) -> cb.conjunction();
        if (clientId != null) spec = spec.and((r, query, cb) -> cb.equal(r.get("clientId"), clientId));
        if (status != null) spec = spec.and((r, query, cb) -> cb.equal(r.get("status"), status));
        if (type != null) spec = spec.and((r, query, cb) -> cb.equal(r.get("documentType"), type));
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((r, query, cb) -> cb.like(cb.lower(r.get("originalFilename")), like));
        }
        return PageResponse.of(documents.findAll(spec, pageable), d -> toDto(d, null));
    }

    @Transactional(readOnly = true)
    public DocumentDto get(UUID id) {
        return toDto(find(id), null);
    }

    @Transactional
    public DownloadedFile download(UUID id) {
        Document doc = find(id);
        byte[] data = encryptor.decrypt(storage.get(doc.getStorageKey()));
        if (!BlindIndex.sha256Hex(data).equals(doc.getSha256())) {
            log.error("Integrity check failed for document {}", id);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTEGRITY_FAILURE",
                    "The stored file failed its integrity check");
        }
        audit.record("DOCUMENT_DOWNLOADED", "DOCUMENT", id, doc.getClientId(),
                Map.of("filename", doc.getOriginalFilename()));
        return new DownloadedFile(doc.getOriginalFilename(), doc.getContentType(), data);
    }

    @Transactional
    public DocumentDto review(UUID id, boolean approve, String comment) {
        CurrentUser me = CurrentUser.require();
        Document doc = find(id);
        clients.findOpen(doc.getClientId());
        if (!Document.Status.AWAITING_REVIEW.contains(doc.getStatus())) {
            throw ApiException.conflict("INVALID_TRANSITION", "Only documents awaiting review can be "
                    + (approve ? "approved" : "rejected"));
        }
        if (me.id().equals(doc.getUploadedBy())) {
            throw ApiException.forbidden("MAKER_CHECKER", "You uploaded this document, so a different user must review it");
        }
        String trimmed = comment == null || comment.isBlank() ? null : comment.trim();
        if (!approve && trimmed == null) {
            throw ApiException.field("comment", "Give a reason for rejecting the document");
        }
        doc.review(approve ? Document.Status.APPROVED : Document.Status.REJECTED, me.id(), trimmed);
        documents.save(doc);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("filename", doc.getOriginalFilename());
        if (trimmed != null) details.put("comment", trimmed);
        audit.record(approve ? "DOCUMENT_APPROVED" : "DOCUMENT_REJECTED", "DOCUMENT", id, doc.getClientId(), details);
        return toDto(doc, null);
    }

    @Transactional
    public DocumentDto archive(UUID id) {
        Document doc = find(id);
        if (doc.getStatus() == Document.Status.ARCHIVED) {
            return toDto(doc, null);
        }
        if (Document.Status.AWAITING_REVIEW.contains(doc.getStatus())
                && doc.getStatus() != Document.Status.DUPLICATE_SUSPECTED) {
            throw ApiException.conflict("INVALID_TRANSITION",
                    "Review the document before archiving it (suspected duplicates may be archived directly)");
        }
        doc.archive();
        documents.save(doc);
        audit.record("DOCUMENT_ARCHIVED", "DOCUMENT", id, doc.getClientId(),
                Map.of("filename", doc.getOriginalFilename()));
        return toDto(doc, null);
    }

    private Document find(UUID id) {
        return documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
    }

    private DocumentDto toDto(Document d, Client knownClient) {
        Client client = knownClient != null ? knownClient : clientRepository.findById(d.getClientId()).orElse(null);
        CurrentUser me = CurrentUser.orNull();
        boolean canReview = me != null && me.has("DOCUMENT_REVIEW") && !me.id().equals(d.getUploadedBy())
                && Document.Status.AWAITING_REVIEW.contains(d.getStatus());
        return new DocumentDto(d.getId(), d.getClientId(), client == null ? null : client.getClientCode(),
                client == null ? null : client.getDisplayName(), d.getDocumentType(), d.getOriginalFilename(),
                d.getContentType(), d.getSizeBytes(), d.getSha256(), d.getFinancialYear(), d.getStatus(),
                d.getScanStatus(), d.getDuplicateOfId(), UserRef.of(users.findById(d.getUploadedBy()).orElse(null)),
                d.getUploadedAt(),
                d.getReviewedBy() == null ? null : UserRef.of(users.findById(d.getReviewedBy()).orElse(null)),
                d.getReviewedAt(), d.getReviewComment(), d.getVersion(), canReview);
    }

    static String validateFinancialYear(String fy) {
        if (fy == null || fy.isBlank()) {
            return null;
        }
        var m = FINANCIAL_YEAR.matcher(fy.trim());
        if (!m.matches()) {
            throw ApiException.field("financialYear", "Use the format 2025-26");
        }
        int start = Integer.parseInt(m.group(1));
        int endShort = Integer.parseInt(m.group(2));
        if ((start + 1) % 100 != endShort || start < 1990 || start > 2100) {
            throw ApiException.field("financialYear", "Financial year must be consecutive years, e.g. 2025-26");
        }
        return fy.trim();
    }

    private void safeDelete(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not remove orphaned object after failed save");
        }
    }
}
