package com.taxpilot.banking;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.taxpilot.common.ApiException;

/**
 * Turns a bank statement export (CSV, XLS, XLSX) into normalised rows.
 * The header row is located by matching column names against aliases used by common Indian bank exports
 * (HDFC, ICICI, SBI, Axis, Kotak and similar), so column order and extra preamble rows do not matter.
 * Nothing is guessed silently: rows that cannot be read are reported as warnings.
 */
public final class BankStatementParser {

    public enum Format { HDFC, ICICI, SBI, AXIS, KOTAK, GENERIC }

    public record Row(int rowNo, LocalDate date, LocalDate valueDate, String description, String reference,
                      BigDecimal withdrawal, BigDecimal deposit, BigDecimal balance) {
    }

    public record Result(Format format, List<Row> rows, BigDecimal openingBalance, BigDecimal closingBalance,
                         List<String> warnings) {
    }

    private enum Col { DATE, VALUE_DATE, DESCRIPTION, REFERENCE, WITHDRAWAL, DEPOSIT, BALANCE, AMOUNT, DRCR }

    private static final Map<String, Col> ALIASES = new HashMap<>();

    static {
        alias(Col.DATE, "date", "txndate", "transactiondate", "trandate", "postingdate", "transactiondt", "txnpostingdate");
        alias(Col.VALUE_DATE, "valuedate", "valuedt", "valdate");
        alias(Col.DESCRIPTION, "narration", "description", "transactionremarks", "particulars", "remarks",
                "details", "transactiondetails", "transactiondescription");
        alias(Col.REFERENCE, "chqrefno", "chequenumber", "chqno", "refnochequeno", "chqrefnumber", "chequeno",
                "referenceno", "refno", "utrnumber", "chequerefno", "instrumentno", "chqnorefno");
        alias(Col.WITHDRAWAL, "withdrawalamt", "withdrawalamountinr", "withdrawal", "withdrawals", "debit",
                "debitamount", "dr", "withdrawalamount", "withdrawaldr", "debitamt", "debitinr");
        alias(Col.DEPOSIT, "depositamt", "depositamountinr", "deposit", "deposits", "credit", "creditamount", "cr",
                "depositamount", "depositcr", "creditamt", "creditinr");
        alias(Col.BALANCE, "closingbalance", "balance", "balanceinr", "bal", "availablebalance", "runningbalance",
                "balanceamt");
        alias(Col.AMOUNT, "amount", "transactionamount", "amountinr", "txnamount");
        alias(Col.DRCR, "drcr", "debitcredit", "crdr", "type", "drcrindicator");
    }

    private static void alias(Col col, String... names) {
        for (String n : names) ALIASES.put(n, col);
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            f("yyyy-MM-dd"), f("dd/MM/yyyy"), f("dd/MM/yy"), f("d/M/yyyy"), f("d/M/yy"), f("dd-MM-yyyy"),
            f("dd-MM-yy"), f("dd.MM.yyyy"), f("dd MMM yyyy"), f("d MMM yyyy"), f("dd-MMM-yyyy"), f("dd-MMM-yy"),
            f("d-MMM-yyyy"), f("dd MMM yy"), f("dd/MMM/yyyy"), f("MMM dd, yyyy"));

    private static DateTimeFormatter f(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
    }

    private BankStatementParser() {
    }

    // ---------------------------------------------------------------- entry point

    public static Result parse(String filename, byte[] data) {
        String ext = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        List<List<String>> table;
        if (ext.endsWith(".xlsx") || ext.endsWith(".xls")) {
            table = readWorkbook(data);
        } else if (ext.endsWith(".csv") || ext.endsWith(".txt")) {
            table = readCsv(decode(data));
        } else {
            throw ApiException.field("file", "Upload the statement as CSV, XLS or XLSX");
        }
        return interpret(table);
    }

    // ---------------------------------------------------------------- interpretation

    static Result interpret(List<List<String>> table) {
        int headerRow = -1;
        Map<Col, Integer> cols = null;
        for (int r = 0; r < Math.min(table.size(), 40) && cols == null; r++) {
            Map<Col, Integer> found = mapHeader(table.get(r));
            if (found != null) {
                cols = found;
                headerRow = r;
            }
        }
        if (cols == null) {
            throw ApiException.field("file", "Could not find the transaction header row (expected columns such as "
                    + "Date, Narration/Description, Withdrawal/Debit, Deposit/Credit, Balance)");
        }
        Format format = detect(table.get(headerRow));
        List<String> warnings = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        BigDecimal explicitOpening = null;
        for (int r = headerRow + 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            if (cells.stream().allMatch(String::isBlank)) {
                if (!rows.isEmpty()) break; // a blank row after the transactions ends the table (summary follows)
                continue;
            }
            String rawDate = cell(cells, cols.get(Col.DATE));
            String desc = cell(cells, cols.get(Col.DESCRIPTION));
            LocalDate date = parseDate(rawDate);
            if (date == null) {
                if (rows.isEmpty() && (desc.toLowerCase(Locale.ROOT).contains("opening balance")
                        || rawDate.toLowerCase(Locale.ROOT).contains("opening balance"))) {
                    explicitOpening = parseAmount(cell(cells, cols.get(Col.BALANCE)));
                    continue;
                }
                // Banner rows such as "***" separators or summary blocks after the transactions.
                if (!rawDate.isBlank() && !rawDate.startsWith("*") && !rows.isEmpty()) {
                    warnings.add("Row " + (r + 1) + ": skipped (unreadable date '" + truncate(rawDate, 30) + "')");
                }
                continue;
            }
            BigDecimal wd;
            BigDecimal dep;
            if (cols.containsKey(Col.WITHDRAWAL) || cols.containsKey(Col.DEPOSIT)) {
                wd = nz(parseAmount(cell(cells, cols.get(Col.WITHDRAWAL))));
                dep = nz(parseAmount(cell(cells, cols.get(Col.DEPOSIT))));
            } else {
                BigDecimal amt = nz(parseAmount(cell(cells, cols.get(Col.AMOUNT))));
                String drcr = cell(cells, cols.get(Col.DRCR)).toUpperCase(Locale.ROOT);
                boolean debit = drcr.startsWith("D") || drcr.contains("DR") || drcr.contains("DEBIT");
                wd = debit ? amt.abs() : BigDecimal.ZERO;
                dep = debit ? BigDecimal.ZERO : amt.abs();
            }
            wd = wd.abs().setScale(2, RoundingMode.UNNECESSARY);
            dep = dep.abs().setScale(2, RoundingMode.UNNECESSARY);
            if (wd.signum() == 0 && dep.signum() == 0) {
                if (rows.isEmpty() && desc.toLowerCase(Locale.ROOT).contains("opening balance")) {
                    explicitOpening = parseAmount(cell(cells, cols.get(Col.BALANCE)));
                } else {
                    warnings.add("Row " + (r + 1) + ": skipped (no withdrawal or deposit amount)");
                }
                continue;
            }
            if (wd.signum() > 0 && dep.signum() > 0) {
                warnings.add("Row " + (r + 1) + ": skipped (both withdrawal and deposit present)");
                continue;
            }
            if (desc.isBlank()) desc = "(no description)";
            rows.add(new Row(r + 1, date, parseDate(cell(cells, cols.get(Col.VALUE_DATE))), truncate(desc, 500),
                    blankToNull(truncate(cell(cells, cols.get(Col.REFERENCE)), 100)), wd, dep,
                    parseAmount(cell(cells, cols.get(Col.BALANCE)))));
        }
        if (rows.isEmpty()) {
            throw ApiException.field("file", "No transactions were found in the statement");
        }
        // Some banks list newest first: normalise to chronological order.
        if (rows.getFirst().date().isAfter(rows.getLast().date())) {
            Collections.reverse(rows);
        }
        BigDecimal opening = explicitOpening;
        BigDecimal closing = rows.getLast().balance();
        Row first = rows.getFirst();
        if (opening == null && first.balance() != null) {
            opening = first.balance().subtract(first.deposit()).add(first.withdrawal());
        }
        int mismatches = 0;
        BigDecimal running = opening;
        for (Row row : rows) {
            if (running != null && row.balance() != null) {
                BigDecimal expected = running.add(row.deposit()).subtract(row.withdrawal());
                if (expected.compareTo(row.balance()) != 0) mismatches++;
                running = row.balance();
            } else {
                running = row.balance();
            }
        }
        if (mismatches > 0) {
            warnings.add(mismatches + " row(s) do not follow the running balance; check the file for missing or reordered rows");
        }
        return new Result(format, rows, opening, closing, warnings);
    }

    private static Map<Col, Integer> mapHeader(List<String> cells) {
        Map<Col, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            Col c = ALIASES.get(norm(cells.get(i)));
            if (c != null && !map.containsKey(c)) map.put(c, i);
        }
        boolean amounts = map.containsKey(Col.WITHDRAWAL) && map.containsKey(Col.DEPOSIT)
                || map.containsKey(Col.AMOUNT) && map.containsKey(Col.DRCR);
        return map.containsKey(Col.DATE) && map.containsKey(Col.DESCRIPTION) && amounts ? map : null;
    }

    static Format detect(List<String> header) {
        Set<String> h = new java.util.HashSet<>();
        header.forEach(c -> h.add(norm(c)));
        if (h.contains("chqrefno") && h.contains("withdrawalamt")) return Format.HDFC;
        if (h.contains("transactionremarks")) return Format.ICICI;
        if (h.contains("txndate") && h.contains("refnochequeno")) return Format.SBI;
        if (h.contains("trandate") && h.contains("particulars")) return Format.AXIS;
        if (h.contains("drcr") && h.contains("chqrefno")) return Format.KOTAK;
        return Format.GENERIC;
    }

    // ---------------------------------------------------------------- value parsing

    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        int space = s.indexOf(' ');
        // Strip a time component such as "01/04/2026 10:15:00".
        if (space > 0 && s.substring(space + 1).matches("\\d{1,2}:\\d{2}.*")) s = s.substring(0, space);
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                LocalDate d = LocalDate.parse(s, f);
                if (d.getYear() < 100) d = d.plusYears(2000);
                return d;
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    static BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT).replace("INR", "").replace("RS.", "").replace("₹", "")
                .replace(",", "").replace(" ", "");
        if (s.isEmpty() || s.equals("-") || s.equals("--")) return null;
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        }
        if (s.endsWith("DR")) {
            negative = true;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("CR")) {
            s = s.substring(0, s.length() - 2);
        }
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.stripTrailingZeros().scale() > 2) {
                throw ApiException.field("file", "Amount '" + truncate(raw, 30) + "' has more than two decimals");
            }
            v = v.setScale(2, RoundingMode.UNNECESSARY);
            return negative ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- file readers

    static String decode(byte[] data) {
        int offset = data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, data.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, offset, data.length - offset, Charset.forName("windows-1252"));
        }
    }

    /** RFC 4180 CSV with quoted fields, embedded commas, doubled quotes and CRLF/LF line endings. */
    static List<List<String>> readCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString().trim());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString().trim());
            rows.add(row);
        }
        return rows;
    }

    static List<List<String>> readWorkbook(byte[] data) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            List<List<String>> rows = new ArrayList<>();
            int last = Math.min(sheet.getLastRowNum(), 20_000);
            for (int r = 0; r <= last; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
                        cells.add(cellText(row.getCell(c), fmt));
                    }
                }
                rows.add(cells);
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) throw api;
            throw ApiException.field("file", "The spreadsheet could not be read. Save it again as XLSX or CSV.");
        }
    }

    private static String cellText(Cell cell, DataFormatter fmt) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            }
            return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return fmt.formatCellValue(cell).trim();
    }

    // ---------------------------------------------------------------- helpers

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String cell(List<String> cells, Integer idx) {
        if (idx == null || idx >= cells.size()) return "";
        String v = cells.get(idx);
        return v == null ? "" : v.trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || s.equals("0") ? null : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
