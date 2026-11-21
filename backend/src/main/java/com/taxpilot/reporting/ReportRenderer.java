package com.taxpilot.reporting;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/** Renders a {@link ReportDocument} to PDF (OpenPDF) or XLSX (Apache POI). */
public final class ReportRenderer {

    static final Color PRIMARY = new Color(0x17, 0x3F, 0x35);
    static final Color MINT = new Color(0xE8, 0xF3, 0xEE);
    static final Color MUTED = new Color(0x69, 0x75, 0x6F);
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private ReportRenderer() {
    }

    // ---------------------------------------------------------------- PDF

    public static byte[] pdf(ReportDocument d) {
        boolean landscape = d.columns.size() > 6;
        Document doc = new Document(landscape ? PageSize.A4.rotate() : PageSize.A4, 36, 36, 36, 42);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        com.lowagie.text.Font tiny = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, MUTED);
        com.lowagie.text.Font small = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
        com.lowagie.text.Font smallBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f);
        com.lowagie.text.Font heading = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, PRIMARY);
        com.lowagie.text.Font sub = FontFactory.getFont(FontFactory.HELVETICA, 9.5f);

        Paragraph lh = new Paragraph(d.letterhead.name().toUpperCase(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, PRIMARY));
        lh.setAlignment(Element.ALIGN_CENTER);
        doc.add(lh);
        String lhLine = joinNonBlank(" · ", d.letterhead.address(),
                d.letterhead.gstin() == null ? null : "GSTIN " + d.letterhead.gstin(), d.letterhead.contact());
        if (!lhLine.isEmpty()) {
            Paragraph p = new Paragraph(lhLine, tiny);
            p.setAlignment(Element.ALIGN_CENTER);
            doc.add(p);
        }
        Paragraph client = new Paragraph(d.clientLine, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11));
        client.setAlignment(Element.ALIGN_CENTER);
        client.setSpacingBefore(8);
        doc.add(client);
        Paragraph title = new Paragraph(pdfSafe(d.title), heading);
        title.setAlignment(Element.ALIGN_CENTER);
        doc.add(title);
        Paragraph period = new Paragraph(pdfSafe(d.periodLine), sub);
        period.setAlignment(Element.ALIGN_CENTER);
        period.setSpacingAfter(10);
        doc.add(period);

        PdfPTable table = new PdfPTable(d.columns.size());
        table.setWidthPercentage(100);
        float[] widths = new float[d.columns.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = d.columns.get(i).width();
        table.setWidths(widths);
        table.setHeaderRows(1);
        for (ReportDocument.Column c : d.columns) {
            PdfPCell cell = new PdfPCell(new Phrase(c.label(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE)));
            cell.setBackgroundColor(PRIMARY);
            cell.setHorizontalAlignment(c.numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            cell.setPadding(4);
            cell.setBorder(Rectangle.NO_BORDER);
            table.addCell(cell);
        }
        for (ReportDocument.Row r : d.rows) {
            for (int i = 0; i < d.columns.size(); i++) {
                ReportDocument.Column c = d.columns.get(i);
                String raw = i < r.cells().size() ? r.cells().get(i) : "";
                String text = c.numeric() && isNumber(raw) ? ReportDocument.inr(raw) : raw;
                com.lowagie.text.Font f = switch (r.style()) {
                    case SECTION, TOTAL, SUBTOTAL -> smallBold;
                    case MUTED -> tiny;
                    default -> small;
                };
                PdfPCell cell = new PdfPCell(new Phrase(pdfSafe(text), f));
                cell.setHorizontalAlignment(c.numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                cell.setPadding(3.5f);
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(new Color(0xE2, 0xE7, 0xE4));
                if (r.style() == ReportDocument.Style.SECTION) cell.setBackgroundColor(new Color(0xF7, 0xF8, 0xF7));
                if (r.style() == ReportDocument.Style.TOTAL) {
                    cell.setBackgroundColor(MINT);
                    cell.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
                    cell.setBorderColor(PRIMARY);
                }
                table.addCell(cell);
            }
        }
        doc.add(table);
        for (String n : d.notes) {
            Paragraph p = new Paragraph(pdfSafe(n), tiny);
            p.setSpacingBefore(4);
            doc.add(p);
        }
        Paragraph foot = new Paragraph("Generated by " + d.generatedBy + " on " + STAMP.format(d.generatedAt)
                + (d.letterhead.footer() != null ? " · " + d.letterhead.footer() : ""), tiny);
        foot.setSpacingBefore(10);
        doc.add(foot);
        doc.close();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- XLSX

    public static byte[] xlsx(ReportDocument d) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheetName(d.title));
            CellStyle bold = wb.createCellStyle();
            Font bf = wb.createFont();
            bf.setBold(true);
            bold.setFont(bf);
            CellStyle titleStyle = wb.createCellStyle();
            Font tf = wb.createFont();
            tf.setBold(true);
            tf.setFontHeightInPoints((short) 13);
            titleStyle.setFont(tf);
            CellStyle header = wb.createCellStyle();
            Font hf = wb.createFont();
            hf.setBold(true);
            hf.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(hf);
            header.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            // Indian grouping: 1,00,00,000.00 / 1,00,000.00 / 10,000.00
            short fmt = wb.createDataFormat().getFormat("[>=10000000]##\\,##\\,##\\,##0.00;[>=100000]##\\,##\\,##0.00;##,##0.00");
            CellStyle num = wb.createCellStyle();
            num.setDataFormat(fmt);
            CellStyle numBold = wb.createCellStyle();
            numBold.setDataFormat(fmt);
            numBold.setFont(bf);
            CellStyle numTotal = wb.createCellStyle();
            numTotal.setDataFormat(fmt);
            numTotal.setFont(bf);
            numTotal.setBorderTop(BorderStyle.THIN);
            numTotal.setBorderBottom(BorderStyle.DOUBLE);

            int r = 0;
            sheet.createRow(r++).createCell(0).setCellValue(d.letterhead.name());
            Cell c0 = sheet.createRow(r++).createCell(0);
            c0.setCellValue(d.clientLine);
            c0.setCellStyle(bold);
            Cell c1 = sheet.createRow(r++).createCell(0);
            c1.setCellValue(d.title);
            c1.setCellStyle(titleStyle);
            sheet.createRow(r++).createCell(0).setCellValue(d.periodLine);
            r++;
            org.apache.poi.ss.usermodel.Row hr = sheet.createRow(r++);
            for (int i = 0; i < d.columns.size(); i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(d.columns.get(i).label());
                CellStyle hs = wb.createCellStyle();
                hs.cloneStyleFrom(header);
                if (d.columns.get(i).numeric()) hs.setAlignment(HorizontalAlignment.RIGHT);
                c.setCellStyle(hs);
            }
            for (ReportDocument.Row row : d.rows) {
                org.apache.poi.ss.usermodel.Row xr = sheet.createRow(r++);
                boolean strong = row.style() == ReportDocument.Style.SECTION || row.style() == ReportDocument.Style.SUBTOTAL;
                for (int i = 0; i < d.columns.size(); i++) {
                    String raw = i < row.cells().size() ? row.cells().get(i) : "";
                    Cell c = xr.createCell(i);
                    if (d.columns.get(i).numeric() && isNumber(raw)) {
                        c.setCellValue(new BigDecimal(raw).doubleValue());
                        c.setCellStyle(row.style() == ReportDocument.Style.TOTAL ? numTotal : strong ? numBold : num);
                    } else {
                        c.setCellValue(raw == null ? "" : raw);
                        if (strong || row.style() == ReportDocument.Style.TOTAL) c.setCellStyle(bold);
                    }
                }
            }
            r++;
            for (String n : d.notes) sheet.createRow(r++).createCell(0).setCellValue(n);
            sheet.createRow(r).createCell(0).setCellValue("Generated by " + d.generatedBy + " on " + STAMP.format(d.generatedAt));
            for (int i = 0; i < d.columns.size(); i++) {
                sheet.setColumnWidth(i, Math.min(255, Math.max(10, (int) (d.columns.get(i).width() * 1.2))) * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The standard PDF fonts have no rupee glyph; spell it out so it never renders as a blank box. */
    static String pdfSafe(String s) {
        return s == null ? "" : s.replace("₹ ", "Rs. ").replace("₹", "Rs. ").replace("Rs. )", "Rs.)");
    }

    static boolean isNumber(String s) {
        return s != null && s.matches("^-?\\d+(\\.\\d+)?$");
    }

    static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) sb.append(sep);
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static String safeSheetName(String s) {
        String n = s.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return n.length() > 31 ? n.substring(0, 31) : n;
    }
}
