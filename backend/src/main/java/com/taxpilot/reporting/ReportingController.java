package com.taxpilot.reporting;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportingController {

    private final ReportExportService exports;
    private final TaxInvoicePdf invoicePdf;

    public ReportingController(ReportExportService exports, TaxInvoicePdf invoicePdf) {
        this.exports = exports;
        this.invoicePdf = invoicePdf;
    }

    @GetMapping("/api/v1/accounting/export")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public ResponseEntity<byte[]> export(@RequestParam ReportExportService.Report report,
                                         @RequestParam(defaultValue = "PDF") String format,
                                         @RequestParam UUID clientId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                         @RequestParam(required = false) UUID accountId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareFrom,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareAsOf) {
        ReportExportService.Format f = ReportExportService.Format.valueOf(format.toUpperCase());
        ReportExportService.Exported e = exports.export(report, f, clientId, from, to, asOf, accountId, compareFrom, compareTo, compareAsOf);
        return file(e.filename(), e.contentType(), e.data());
    }

    @GetMapping("/api/v1/invoices/{id}/pdf")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID id) {
        TaxInvoicePdf.Rendered r = invoicePdf.render(id);
        return file(r.filename(), "application/pdf", r.data());
    }

    private static ResponseEntity<byte[]> file(String name, String type, byte[] data) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(type))
                .body(data);
    }
}
