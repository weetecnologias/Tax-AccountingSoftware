package com.taxpilot.invoicing;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.accounting.AccountingDtos.ReasonRequest;
import com.taxpilot.common.PageResponse;
import com.taxpilot.common.Paging;
import com.taxpilot.invoicing.InvoiceService.InvoiceDto;
import com.taxpilot.invoicing.InvoiceService.InvoiceRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    public record PrefixRequest(String prefix) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public PageResponse<InvoiceDto> list(@RequestParam UUID clientId,
                                         @RequestParam(required = false) Invoice.Type type,
                                         @RequestParam(required = false) Invoice.Status status,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return service.list(clientId, type, status, q, Paging.of(page, size, null, Set.of("invoiceDate"), "invoiceDate"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public InvoiceDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/gst-rates")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<InvoiceService.RateDto> rates(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.rates(date);
    }

    @GetMapping("/series")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<InvoiceService.SeriesDto> series(@RequestParam UUID clientId) {
        return service.series(clientId);
    }

    @PutMapping("/series/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_APPROVE')")
    public InvoiceService.SeriesDto updateSeries(@PathVariable UUID id, @RequestBody PrefixRequest r) {
        return service.updateSeriesPrefix(id, r.prefix());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public ResponseEntity<InvoiceDto> create(@Valid @RequestBody InvoiceRequest r,
                                             @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        InvoiceService.CreateResult res = service.create(r, key);
        return ResponseEntity.status(res.created() ? HttpStatus.CREATED : HttpStatus.OK).body(res.invoice());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public InvoiceDto update(@PathVariable UUID id, @Valid @RequestBody InvoiceRequest r) {
        return service.update(id, r);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public InvoiceDto submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('ACCOUNTING_APPROVE')")
    public InvoiceDto approve(@PathVariable UUID id) {
        return service.approveAndPost(id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('ACCOUNTING_APPROVE')")
    public InvoiceDto reject(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest r) {
        return service.reject(id, r == null ? null : r.reason());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public InvoiceDto cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
