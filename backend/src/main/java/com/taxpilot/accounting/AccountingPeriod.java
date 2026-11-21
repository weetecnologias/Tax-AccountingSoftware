package com.taxpilot.accounting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.taxpilot.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** One calendar month of a client's financial year. Postings are allowed only into OPEN periods. */
@Entity
@Table(name = "accounting_periods")
public class AccountingPeriod extends BaseEntity {

    public enum Status { OPEN, CLOSED }

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(name = "financial_year", nullable = false, updatable = false)
    private String financialYear;

    @Column(name = "period_number", nullable = false, updatable = false)
    private short periodNumber;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected AccountingPeriod() {
    }

    public AccountingPeriod(UUID clientId, String financialYear, int periodNumber, LocalDate start, LocalDate end) {
        this.clientId = clientId;
        this.financialYear = financialYear;
        this.periodNumber = (short) periodNumber;
        this.startDate = start;
        this.endDate = end;
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public void close(UUID by) {
        status = Status.CLOSED;
        closedBy = by;
        closedAt = Instant.now();
    }

    public void reopen() {
        status = Status.OPEN;
        closedBy = null;
        closedAt = null;
    }

    public UUID getClientId() { return clientId; }
    public String getFinancialYear() { return financialYear; }
    public short getPeriodNumber() { return periodNumber; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public Status getStatus() { return status; }
    public UUID getClosedBy() { return closedBy; }
    public Instant getClosedAt() { return closedAt; }
    public long getVersion() { return version; }
}
