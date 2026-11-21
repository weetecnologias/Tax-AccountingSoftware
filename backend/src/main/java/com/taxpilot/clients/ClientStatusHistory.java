package com.taxpilot.clients;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "client_status_history")
public class ClientStatusHistory {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ClientStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private ClientStatus toStatus;

    @Column(name = "changed_by", nullable = false)
    private UUID changedBy;

    private String comment;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected ClientStatusHistory() {
    }

    public ClientStatusHistory(UUID clientId, ClientStatus from, ClientStatus to, UUID changedBy, String comment) {
        this.id = UUID.randomUUID();
        this.clientId = clientId;
        this.fromStatus = from;
        this.toStatus = to;
        this.changedBy = changedBy;
        this.comment = comment;
        this.changedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public ClientStatus getFromStatus() { return fromStatus; }
    public ClientStatus getToStatus() { return toStatus; }
    public UUID getChangedBy() { return changedBy; }
    public String getComment() { return comment; }
    public Instant getChangedAt() { return changedAt; }
}
