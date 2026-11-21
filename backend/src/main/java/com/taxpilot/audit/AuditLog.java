package com.taxpilot.audit;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String details;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "request_id")
    private String requestId;

    protected AuditLog() {
    }

    AuditLog(UUID actorUserId, String actorEmail, String action, String entityType, UUID entityId, UUID clientId,
             String details, String ipAddress, String requestId) {
        this.id = UUID.randomUUID();
        this.occurredAt = Instant.now();
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.clientId = clientId;
        this.details = details;
        this.ipAddress = ipAddress;
        this.requestId = requestId;
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getActorUserId() { return actorUserId; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public UUID getClientId() { return clientId; }
    public String getDetails() { return details; }
    public String getIpAddress() { return ipAddress; }
    public String getRequestId() { return requestId; }
}
