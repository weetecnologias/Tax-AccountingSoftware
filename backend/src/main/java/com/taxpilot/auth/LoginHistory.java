package com.taxpilot.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "login_history")
public class LoginHistory {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LoginHistory() {
    }

    public LoginHistory(UUID userId, String email, boolean success, String failureReason, String ipAddress,
                        String userAgent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.email = email.length() > 254 ? email.substring(0, 254) : email;
        this.success = success;
        this.failureReason = failureReason;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.occurredAt = Instant.now();
    }

    public boolean isSuccess() { return success; }
    public String getFailureReason() { return failureReason; }
    public UUID getUserId() { return userId; }
}
