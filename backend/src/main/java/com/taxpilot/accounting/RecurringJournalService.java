package com.taxpilot.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.accounting.AccountingDtos.JournalLineRequest;
import com.taxpilot.accounting.AccountingDtos.JournalRequest;
import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.ClientService;
import com.taxpilot.common.ApiException;
import com.taxpilot.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Recurring journal templates. "Generate due drafts" creates one DRAFT journal per due date (idempotent per template
 * and date); every draft still needs submission, approval and posting.
 */
@Service
public class RecurringJournalService {

    public interface RecurringJournalRepository extends JpaRepository<RecurringJournal, UUID> {
        List<RecurringJournal> findByClientIdOrderByNameAsc(UUID clientId);
    }

    public record TemplateRequest(@NotNull UUID clientId, @NotBlank @Size(max = 120) String name,
                                  @NotBlank @Size(max = 1000) String narration, @Size(max = 100) String reference,
                                  @NotNull RecurringJournal.Frequency frequency, @NotNull LocalDate nextDate,
                                  LocalDate endDate, Boolean active,
                                  @NotEmpty @Size(max = 100) List<@Valid JournalLineRequest> lines, Long version) {
    }

    public record TemplateLineDto(UUID accountId, UUID partyId, String debit, String credit, String description) {
    }

    public record TemplateDto(UUID id, String name, String narration, String reference,
                              RecurringJournal.Frequency frequency, LocalDate nextDate, LocalDate endDate,
                              boolean active, String amount, List<TemplateLineDto> lines, long version) {
    }

    public record GenerateRequest(@NotNull UUID clientId, @NotNull LocalDate asOf) {
    }

    public record GenerateResult(List<UUID> createdJournalIds, List<String> skipped) {
    }

    private final RecurringJournalRepository repo;
    private final JournalService journals;
    private final ClientService clients;
    private final AuditService audit;
    private final AccountingRepositories.PeriodRepository periodRepo;

    public RecurringJournalService(RecurringJournalRepository repo, JournalService journals, ClientService clients,
                                   AuditService audit, AccountingRepositories.PeriodRepository periodRepo) {
        this.repo = repo;
        this.journals = journals;
        this.clients = clients;
        this.audit = audit;
        this.periodRepo = periodRepo;
    }

    @Transactional(readOnly = true)
    public List<TemplateDto> list(UUID clientId) {
        clients.find(clientId);
        return repo.findByClientIdOrderByNameAsc(clientId).stream().map(RecurringJournalService::toDto).toList();
    }

    @Transactional
    public TemplateDto save(UUID id, TemplateRequest r) {
        CurrentUser me = CurrentUser.require();
        clients.findOpen(r.clientId());
        RecurringJournal t;
        if (id == null) {
            t = new RecurringJournal(r.clientId(), me.id());
        } else {
            t = repo.findById(id).filter(x -> x.getClientId().equals(r.clientId()))
                    .orElseThrow(() -> ApiException.notFound("Recurring journal"));
            if (r.version() == null || r.version() != t.getVersion()) {
                throw ApiException.conflict("STALE_VERSION", "This template was changed by someone else. Reload and try again.");
            }
        }
        if (r.endDate() != null && r.endDate().isBefore(r.nextDate())) {
            throw ApiException.field("endDate", "End date cannot be before the next date");
        }
        if (r.lines().size() < 2) throw ApiException.field("lines", "A journal needs at least two lines");
        List<RecurringJournal.Line> lines = new ArrayList<>();
        BigDecimal dr = Money.ZERO;
        BigDecimal cr = Money.ZERO;
        for (int i = 0; i < r.lines().size(); i++) {
            JournalLineRequest l = r.lines().get(i);
            BigDecimal d = Money.parse(l.debit(), "lines[" + i + "].debit");
            BigDecimal c = Money.parse(l.credit(), "lines[" + i + "].credit");
            if ((d.signum() == 0) == (c.signum() == 0)) {
                throw ApiException.field("lines[" + i + "].debit", "Line " + (i + 1) + ": enter either a debit or a credit");
            }
            dr = dr.add(d);
            cr = cr.add(c);
            lines.add(new RecurringJournal.Line(l.accountId(), l.partyId(), d, c,
                    l.description() == null || l.description().isBlank() ? null : l.description().trim()));
        }
        if (dr.compareTo(cr) != 0) throw ApiException.field("lines", "The template must balance (debits equal credits)");
        t.update(r.name().trim(), r.narration().trim(), r.reference() == null || r.reference().isBlank() ? null : r.reference().trim(),
                r.frequency(), r.nextDate(), r.endDate(), r.active() == null || r.active(), lines);
        repo.save(t);
        audit.record(id == null ? "RECURRING_JOURNAL_CREATED" : "RECURRING_JOURNAL_UPDATED", "RECURRING_JOURNAL",
                t.getId(), r.clientId(), Map.of("name", t.getName(), "frequency", t.getFrequency().name(),
                        "nextDate", t.getNextDate().toString(), "active", t.isActive()));
        return toDto(t);
    }

    @Transactional
    public GenerateResult generate(UUID clientId, LocalDate asOf) {
        if (clients.find(clientId).getStatus() != com.taxpilot.clients.ClientStatus.ACTIVE) {
            throw ApiException.conflict("CLIENT_NOT_ACTIVE", "Journals can only be generated for active clients");
        }
        List<UUID> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (RecurringJournal t : repo.findByClientIdOrderByNameAsc(clientId)) {
            int guard = 0;
            while (t.isActive() && !t.getNextDate().isAfter(asOf) && guard++ < 24) {
                // Pre-check so a failure never happens inside the (transactional) create call.
                String problem = periodProblem(clientId, t.getNextDate());
                if (problem != null) {
                    skipped.add(t.getName() + " (" + t.getNextDate() + "): " + problem);
                    break;
                }
                List<JournalLineRequest> lines = t.getLines().stream().map(l -> new JournalLineRequest(l.getAccountId(),
                        l.getDebit().signum() > 0 ? l.getDebit().toPlainString() : null,
                        l.getCredit().signum() > 0 ? l.getCredit().toPlainString() : null, l.getDescription(),
                        l.getPartyId())).toList();
                JournalRequest req = new JournalRequest(clientId, t.getNextDate(), JournalEntry.Type.GENERAL,
                        t.getNarration(), t.getReference(), lines, null);
                var result = journals.create(req, "rec-" + t.getId() + "-" + t.getNextDate());
                if (result.created()) created.add(result.journal().id());
                t.advance();
            }
            repo.save(t);
        }
        audit.record("RECURRING_JOURNALS_GENERATED", "CLIENT", clientId, clientId,
                Map.of("asOf", asOf.toString(), "created", created.size(), "skipped", skipped.size()));
        return new GenerateResult(created, skipped);
    }

    private String periodProblem(UUID clientId, LocalDate date) {
        // Note: Optional.map(...) returning null would turn into "empty", so test the period explicitly.
        var period = periodRepo.findContaining(clientId, date);
        if (period.isEmpty()) return "no financial year is open for this date";
        return period.get().getStatus() == AccountingPeriod.Status.OPEN ? null : "the accounting period is closed";
    }

    private static TemplateDto toDto(RecurringJournal t) {
        BigDecimal amount = t.getLines().stream().map(RecurringJournal.Line::getDebit).reduce(Money.ZERO, BigDecimal::add);
        return new TemplateDto(t.getId(), t.getName(), t.getNarration(), t.getReference(), t.getFrequency(),
                t.getNextDate(), t.getEndDate(), t.isActive(), Money.str(amount), t.getLines().stream()
                .map(l -> new TemplateLineDto(l.getAccountId(), l.getPartyId(), Money.str(l.getDebit()),
                        Money.str(l.getCredit()), l.getDescription())).toList(), t.getVersion());
    }

    @RestController
    @RequestMapping("/api/v1/accounting/recurring")
    public static class Controller {
        private final RecurringJournalService service;

        public Controller(RecurringJournalService service) {
            this.service = service;
        }

        @GetMapping
        @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
        public List<TemplateDto> list(@RequestParam UUID clientId) {
            return service.list(clientId);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
        public TemplateDto create(@Valid @RequestBody TemplateRequest r) {
            return service.save(null, r);
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
        public TemplateDto update(@PathVariable UUID id, @Valid @RequestBody TemplateRequest r) {
            return service.save(id, r);
        }

        @PostMapping("/generate")
        @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
        public GenerateResult generate(@Valid @RequestBody GenerateRequest r) {
            return service.generate(r.clientId(), r.asOf());
        }
    }
}
