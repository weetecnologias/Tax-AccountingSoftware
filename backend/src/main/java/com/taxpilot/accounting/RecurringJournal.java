package com.taxpilot.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.taxpilot.common.BaseEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A template that produces DRAFT journals on a schedule (never posts by itself). */
@Entity
@Table(name = "recurring_journals")
public class RecurringJournal extends BaseEntity {

    public enum Frequency {
        MONTHLY(1), QUARTERLY(3), YEARLY(12);

        final int months;

        Frequency(int months) {
            this.months = months;
        }

        public LocalDate next(LocalDate d) {
            return d.plusMonths(months);
        }
    }

    @Column(name = "client_id", nullable = false, updatable = false) private UUID clientId;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String narration;
    private String reference;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Frequency frequency;
    @Column(name = "next_date", nullable = false) private LocalDate nextDate;
    @Column(name = "end_date") private LocalDate endDate;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
    @Version private long version;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<Line> lines = new ArrayList<>();

    protected RecurringJournal() {
    }

    public RecurringJournal(UUID clientId, UUID createdBy) {
        this.clientId = clientId;
        this.createdBy = createdBy;
    }

    void update(String name, String narration, String reference, Frequency frequency, LocalDate nextDate,
                LocalDate endDate, boolean active, List<Line> newLines) {
        this.name = name;
        this.narration = narration;
        this.reference = reference;
        this.frequency = frequency;
        this.nextDate = nextDate;
        this.endDate = endDate;
        this.active = active;
        lines.clear();
        short n = 1;
        for (Line l : newLines) {
            l.template = this;
            l.lineNo = n++;
            lines.add(l);
        }
    }

    void advance() {
        nextDate = frequency.next(nextDate);
        if (endDate != null && nextDate.isAfter(endDate)) active = false;
    }

    public UUID getClientId() { return clientId; }
    public String getName() { return name; }
    public String getNarration() { return narration; }
    public String getReference() { return reference; }
    public Frequency getFrequency() { return frequency; }
    public LocalDate getNextDate() { return nextDate; }
    public LocalDate getEndDate() { return endDate; }
    public boolean isActive() { return active; }
    public UUID getCreatedBy() { return createdBy; }
    public long getVersion() { return version; }
    public List<Line> getLines() { return lines; }

    @Entity(name = "RecurringJournalLine")
    @Table(name = "recurring_journal_lines")
    public static class Line {
        @Id private UUID id = UUID.randomUUID();
        @ManyToOne(optional = false) @JoinColumn(name = "recurring_journal_id", nullable = false, updatable = false)
        private RecurringJournal template;
        @Column(name = "line_no", nullable = false) private short lineNo;
        @Column(name = "account_id", nullable = false) private UUID accountId;
        @Column(name = "party_id") private UUID partyId;
        @Column(nullable = false) private BigDecimal debit;
        @Column(nullable = false) private BigDecimal credit;
        private String description;

        protected Line() {
        }

        Line(UUID accountId, UUID partyId, BigDecimal debit, BigDecimal credit, String description) {
            this.accountId = accountId;
            this.partyId = partyId;
            this.debit = Money.of(debit);
            this.credit = Money.of(credit);
            this.description = description;
        }

        public UUID getAccountId() { return accountId; }
        public UUID getPartyId() { return partyId; }
        public BigDecimal getDebit() { return debit; }
        public BigDecimal getCredit() { return credit; }
        public String getDescription() { return description; }
        public short getLineNo() { return lineNo; }
    }
}
