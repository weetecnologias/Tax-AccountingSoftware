package com.taxpilot.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.taxpilot.settings.SettingsService;

/** Renderer-neutral tabular report: one model, rendered to PDF or XLSX. */
public final class ReportDocument {

    public enum Style { NORMAL, SECTION, SUBTOTAL, TOTAL, MUTED }

    public record Column(String label, boolean numeric, float width) {
    }

    public record Row(List<String> cells, Style style) {
    }

    final String title;
    final String clientLine;
    final String periodLine;
    final SettingsService.Letterhead letterhead;
    final String generatedBy;
    final Instant generatedAt;
    final List<Column> columns = new ArrayList<>();
    final List<Row> rows = new ArrayList<>();
    final List<String> notes = new ArrayList<>();

    public ReportDocument(String title, String clientLine, String periodLine, SettingsService.Letterhead letterhead,
                          String generatedBy, Instant generatedAt) {
        this.title = title;
        this.clientLine = clientLine;
        this.periodLine = periodLine;
        this.letterhead = letterhead;
        this.generatedBy = generatedBy;
        this.generatedAt = generatedAt;
    }

    public ReportDocument column(String label, boolean numeric, float width) {
        columns.add(new Column(label, numeric, width));
        return this;
    }

    public ReportDocument row(Style style, String... cells) {
        rows.add(new Row(List.of(cells), style));
        return this;
    }

    public ReportDocument row(Style style, List<String> cells) {
        rows.add(new Row(cells, style));
        return this;
    }

    public ReportDocument note(String note) {
        notes.add(note);
        return this;
    }

    public String getTitle() {
        return title;
    }

    /** Indian digit grouping (12,34,567.89) from a plain decimal string; blank stays blank. */
    public static String inr(String value) {
        if (value == null || value.isBlank()) return "";
        BigDecimal v = new BigDecimal(value);
        boolean neg = v.signum() < 0;
        String s = v.abs().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        String intPart = s.substring(0, s.indexOf('.'));
        String frac = s.substring(s.indexOf('.'));
        StringBuilder out = new StringBuilder();
        if (intPart.length() > 3) {
            String head = intPart.substring(0, intPart.length() - 3);
            String tail = intPart.substring(intPart.length() - 3);
            StringBuilder h = new StringBuilder();
            for (int i = head.length(); i > 0; i -= 2) {
                h.insert(0, head.substring(Math.max(0, i - 2), i));
                if (i - 2 > 0) h.insert(0, ',');
            }
            out.append(h).append(',').append(tail);
        } else {
            out.append(intPart);
        }
        return (neg ? "-" : "") + out + frac;
    }
}
