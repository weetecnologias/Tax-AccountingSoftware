package com.taxpilot.audit;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.common.PageResponse;
import com.taxpilot.common.Paging;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('AUDIT_READ')")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audit-logs")
    public PageResponse<AuditService.AuditEntryDto> search(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return auditService.search(actorId, action, entityType, entityId, clientId, from, to,
                Paging.of(page, size, null, Set.of("occurredAt"), "occurredAt"));
    }

    @GetMapping("/clients/{clientId}/audit")
    public PageResponse<AuditService.AuditEntryDto> forClient(@PathVariable UUID clientId,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "25") int size) {
        return auditService.search(null, null, null, null, clientId, null, null,
                Paging.of(page, size, null, Set.of("occurredAt"), "occurredAt"));
    }
}
