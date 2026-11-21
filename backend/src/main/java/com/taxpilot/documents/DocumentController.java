package com.taxpilot.documents;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.taxpilot.common.PageResponse;
import com.taxpilot.common.Paging;
import com.taxpilot.documents.DocumentService.DocumentDto;

import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private static final Set<String> SORTABLE = Set.of("uploadedAt", "originalFilename", "status", "sizeBytes");

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    public record ReviewRequest(@Size(max = 1000) String comment) {
    }

    @PostMapping(path = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DOCUMENT_UPLOAD')")
    public DocumentDto upload(@RequestPart("file") MultipartFile file,
                              @RequestParam UUID clientId,
                              @RequestParam DocumentType documentType,
                              @RequestParam(required = false) String financialYear) {
        return service.upload(clientId, documentType, financialYear, file);
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    public PageResponse<DocumentDto> list(@RequestParam(required = false) UUID clientId,
                                          @RequestParam(required = false) Document.Status status,
                                          @RequestParam(required = false) DocumentType documentType,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) String sort) {
        return service.list(clientId, status, documentType, q, Paging.of(page, size, sort, SORTABLE, "uploadedAt"));
    }

    @GetMapping("/clients/{clientId}/documents")
    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    public PageResponse<DocumentDto> forClient(@PathVariable UUID clientId,
                                               @RequestParam(required = false) Document.Status status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.list(clientId, status, null, null, Paging.of(page, size, null, SORTABLE, "uploadedAt"));
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    public DocumentDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/documents/{id}/download")
    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        DocumentService.DownloadedFile file = service.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.data());
    }

    @PostMapping("/documents/{id}/approve")
    @PreAuthorize("hasAuthority('DOCUMENT_REVIEW')")
    public DocumentDto approve(@PathVariable UUID id, @RequestBody(required = false) ReviewRequest request) {
        return service.review(id, true, request == null ? null : request.comment());
    }

    @PostMapping("/documents/{id}/reject")
    @PreAuthorize("hasAuthority('DOCUMENT_REVIEW')")
    public DocumentDto reject(@PathVariable UUID id, @RequestBody(required = false) ReviewRequest request) {
        return service.review(id, false, request == null ? null : request.comment());
    }

    @PostMapping("/documents/{id}/archive")
    @PreAuthorize("hasAuthority('DOCUMENT_REVIEW')")
    public DocumentDto archive(@PathVariable UUID id) {
        return service.archive(id);
    }
}
