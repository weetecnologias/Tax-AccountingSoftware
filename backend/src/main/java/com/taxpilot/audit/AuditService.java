package com.taxpilot.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.common.PageResponse;
import com.taxpilot.common.RequestContext;
import com.taxpilot.security.CurrentUser;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes append-only audit records. Callers must never pass secrets, full PANs or account numbers in details.
 * Joins the caller's transaction so the audit row commits or rolls back with the change it describes.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final JsonMapper json;

    public AuditService(AuditLogRepository repository, JsonMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional
    public void record(String action, String entityType, UUID entityId, UUID clientId, Map<String, ?> details) {
        CurrentUser actor = CurrentUser.orNull();
        recordAs(actor == null ? null : actor.id(), actor == null ? null : actor.email(), action, entityType,
                entityId, clientId, details);
    }

    /** For security events that must survive even if the surrounding request transaction rolls back. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordIndependently(String action, String entityType, UUID entityId, UUID clientId,
                                    Map<String, ?> details) {
        record(action, entityType, entityId, clientId, details);
    }

    @Transactional
    public void recordAs(UUID actorId, String actorEmail, String action, String entityType, UUID entityId,
                         UUID clientId, Map<String, ?> details) {
        String detailJson = details == null || details.isEmpty() ? null : json.writeValueAsString(details);
        repository.save(new AuditLog(actorId, actorEmail, action, entityType, entityId, clientId, detailJson,
                RequestContext.clientIp(), RequestContext.requestId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntryDto> search(UUID actorId, String action, String entityType, UUID entityId,
                                              UUID clientId, Instant from, Instant to, Pageable pageable) {
        Specification<AuditLog> spec = (root, q, cb) -> cb.conjunction();
        if (actorId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("actorUserId"), actorId));
        if (action != null && !action.isBlank()) spec = spec.and((r, q, cb) -> cb.equal(r.get("action"), action));
        if (entityType != null && !entityType.isBlank())
            spec = spec.and((r, q, cb) -> cb.equal(r.get("entityType"), entityType));
        if (entityId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("entityId"), entityId));
        if (clientId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("clientId"), clientId));
        if (from != null) spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("occurredAt"), from));
        if (to != null) spec = spec.and((r, q, cb) -> cb.lessThan(r.get("occurredAt"), to));
        return PageResponse.of(repository.findAll(spec, pageable), this::toDto);
    }

    public AuditEntryDto toDto(AuditLog log) {
        Object details = log.getDetails() == null ? null : json.readValue(log.getDetails(), Map.class);
        return new AuditEntryDto(log.getId(), log.getOccurredAt(), log.getActorUserId(), log.getActorEmail(),
                log.getAction(), log.getEntityType(), log.getEntityId(), log.getClientId(), details,
                log.getIpAddress(), log.getRequestId());
    }

    public record AuditEntryDto(UUID id, Instant occurredAt, UUID actorUserId, String actorEmail, String action,
                                String entityType, UUID entityId, UUID clientId, Object details, String ipAddress,
                                String requestId) {
    }
}
