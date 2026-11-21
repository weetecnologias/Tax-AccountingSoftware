package com.taxpilot.banking;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import com.taxpilot.accounting.AccountingDtos.ReasonRequest;

import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/bank-reconciliation")
public class BankingController {

    private final ReconciliationService service;

    public BankingController(ReconciliationService service) {
        this.service = service;
    }

    public record MatchRequest(@NotNull UUID lineId) {
    }

    public record JournalDraftRequest(@NotNull UUID counterAccountId, String narration) {
    }

    @GetMapping("/statements")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<ReconciliationService.ImportDto> list(@RequestParam UUID clientId) {
        return service.list(clientId);
    }

    @PostMapping(path = "/statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public ReconciliationService.ImportResult upload(@RequestParam UUID clientId, @RequestParam UUID ledgerAccountId,
                                                     @RequestPart("file") MultipartFile file) {
        return service.importStatement(clientId, ledgerAccountId, file);
    }

    @GetMapping("/statements/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public ReconciliationService.ImportDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/statements/{id}/transactions")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<ReconciliationService.TransactionDto> transactions(@PathVariable UUID id) {
        return service.transactions(id);
    }

    @GetMapping("/statements/{id}/summary")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public ReconciliationService.Summary summary(@PathVariable UUID id) {
        return service.summary(id);
    }

    @PostMapping("/statements/{id}/auto-match")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public Map<String, Integer> autoMatch(@PathVariable UUID id) {
        return Map.of("matched", service.rerunAutoMatch(id));
    }

    @PostMapping("/statements/{id}/approve")
    @PreAuthorize("hasAuthority('ACCOUNTING_APPROVE')")
    public ReconciliationService.ImportDto approve(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest r) {
        return service.approve(id, r == null ? null : r.reason());
    }

    @GetMapping("/transactions/{id}/suggestions")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<ReconciliationService.Suggestion> suggestions(@PathVariable UUID id) {
        return service.suggestions(id);
    }

    @PostMapping("/transactions/{id}/match")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public ReconciliationService.TransactionDto match(@PathVariable UUID id, @RequestBody MatchRequest r) {
        return service.match(id, r.lineId());
    }

    @PostMapping("/transactions/{id}/unmatch")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public ReconciliationService.TransactionDto unmatch(@PathVariable UUID id) {
        return service.unmatch(id);
    }

    @PostMapping("/transactions/{id}/exclude")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public ReconciliationService.TransactionDto exclude(@PathVariable UUID id, @RequestBody ReasonRequest r) {
        return service.exclude(id, r.reason());
    }

    @PostMapping("/transactions/{id}/create-journal")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public Map<String, UUID> createJournal(@PathVariable UUID id, @RequestBody JournalDraftRequest r) {
        return Map.of("journalId", service.createJournal(id, r.counterAccountId(), r.narration()));
    }
}
