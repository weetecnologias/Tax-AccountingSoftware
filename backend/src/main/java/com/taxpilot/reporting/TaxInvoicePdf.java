package com.taxpilot.reporting;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.taxpilot.accounting.Money;
import com.taxpilot.audit.AuditService;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.validation.IndianStates;
import com.taxpilot.invoicing.Invoice;
import com.taxpilot.invoicing.InvoicingRepositories.InvoiceRepository;
import com.taxpilot.settings.SettingsService;

/**
 * Printable GST document for outward supplies: tax invoice (sales), credit note and debit note. Carries the particulars
 * of CGST Rule 46 / 53: supplier and recipient name, address and GSTIN, serial number and date, place of supply with
 * state, HSN/SAC, quantity, taxable value, rate and amount of each tax, reverse-charge flag, and the signatory block.
 * Anything not yet posted is watermarked "DRAFT — not a tax invoice".
 */
@Service
public class TaxInvoicePdf {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final Color LINE = new Color(0xC9, 0xD2, 0xCD);

    public record Rendered(String filename, byte[] data) {
    }

    private final InvoiceRepository invoices;
    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;

    public TaxInvoicePdf(InvoiceRepository invoices, JdbcTemplate jdbc, SettingsService settings, AuditService audit) {
        this.invoices = invoices;
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional
    public Rendered render(UUID invoiceId) {
        Invoice inv = invoices.findById(invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (inv.getInvoiceType() == Invoice.Type.PURCHASE) {
            throw ApiException.badRequest("NOT_OUR_DOCUMENT",
                    "A purchase invoice is the supplier's document; keep the supplier's copy instead.");
        }
        byte[] pdf = build(inv);
        String number = inv.getInvoiceNumber() != null ? inv.getInvoiceNumber().replace('/', '-') : "DRAFT-" + inv.getId().toString().substring(0, 8);
        audit.record("INVOICE_PDF_DOWNLOADED", "INVOICE", inv.getId(), inv.getClientId(),
                Map.of("number", inv.getInvoiceNumber() == null ? "draft" : inv.getInvoiceNumber()));
        return new Rendered(number + ".pdf", pdf);
    }

    String title(Invoice inv) {
        return switch (inv.getInvoiceType()) {
            case CREDIT_NOTE -> "CREDIT NOTE";
            case DEBIT_NOTE -> "DEBIT NOTE";
            default -> "TAX INVOICE";
        };
    }

    private byte[] build(Invoice inv) {
        Map<String, Object> client = jdbc.queryForMap("select legal_name, display_name, email, phone from clients where id = ?", inv.getClientId());
        Map<String, Object> reg = inv.getGstRegistrationId() == null ? Map.of()
                : jdbc.queryForMap("select gstin, trade_name, state_code, principal_place from gst_registrations where id = ?", inv.getGstRegistrationId());
        List<Map<String, Object>> addr = jdbc.queryForList("""
                select line1, line2, city, state_code, pincode from client_addresses where client_id = ?
                order by case address_type when 'REGISTERED' then 0 when 'BUSINESS' then 1 else 2 end, created_at limit 1
                """, inv.getClientId());
        Map<String, Object> party = jdbc.queryForMap("select name, gstin, state_code, address, email, phone from parties where id = ?", inv.getPartyId());
        Map<String, Object> original = inv.getOriginalInvoiceId() == null ? null
                : jdbc.queryForMap("select coalesce(invoice_number, supplier_invoice_number) as num, invoice_date from invoices where id = ?",
                inv.getOriginalInvoiceId());

        Document doc = new Document(PageSize.A4, 30, 30, 30, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        doc.open();
        Font tiny = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, ReportRenderer.MUTED);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
        Font smallBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f);
        Font label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, ReportRenderer.PRIMARY);

        // Supplier block (the client is the supplier on its own outward documents).
        String legalName = (String) client.get("legal_name");
        String tradeName = reg.get("trade_name") != null ? (String) reg.get("trade_name") : (String) client.get("display_name");
        Paragraph supplierName = new Paragraph(legalName, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, ReportRenderer.PRIMARY));
        supplierName.setAlignment(Element.ALIGN_CENTER);
        doc.add(supplierName);
        if (tradeName != null && !tradeName.equalsIgnoreCase(legalName)) center(doc, "Trading as " + tradeName, small);
        String supplierAddress = reg.get("principal_place") != null ? (String) reg.get("principal_place") : address(addr);
        if (supplierAddress != null) center(doc, supplierAddress, small);
        center(doc, ReportRenderer.joinNonBlank("  ·  ",
                reg.get("gstin") == null ? "GSTIN: not registered" : "GSTIN: " + reg.get("gstin"),
                reg.get("state_code") == null ? null : "State: " + state((String) reg.get("state_code")),
                (String) client.get("phone"), (String) client.get("email")), small);

        Paragraph t = new Paragraph(title(inv), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13));
        t.setAlignment(Element.ALIGN_CENTER);
        t.setSpacingBefore(8);
        t.setSpacingAfter(6);
        doc.add(t);

        // Document particulars and recipient, side by side.
        PdfPTable meta = new PdfPTable(new float[]{1, 1});
        meta.setWidthPercentage(100);
        List<String[]> left = new ArrayList<>();
        left.add(new String[]{inv.getInvoiceType().isNote() ? "Note number" : "Invoice number",
                inv.getInvoiceNumber() != null ? inv.getInvoiceNumber() : "Not yet numbered (draft)"});
        left.add(new String[]{"Date", D.format(inv.getInvoiceDate())});
        if (inv.getDueDate() != null && !inv.getInvoiceType().isNote()) left.add(new String[]{"Due date", D.format(inv.getDueDate())});
        left.add(new String[]{"Place of supply", inv.getPlaceOfSupply() + " - " + state(inv.getPlaceOfSupply())});
        left.add(new String[]{"Supply type", inv.getSupplyType() == Invoice.SupplyType.INTRA ? "Intra-state (CGST + SGST)" : "Inter-state (IGST)"});
        left.add(new String[]{"Tax payable on reverse charge", inv.isReverseCharge() ? "Yes" : "No"});
        if (original != null) {
            left.add(new String[]{"Against invoice", original.get("num") + " dated " + D.format(((java.sql.Date) original.get("invoice_date")).toLocalDate())});
            if (inv.getNoteReason() != null) left.add(new String[]{"Reason", inv.getNoteReason()});
        }
        meta.addCell(keyValueCell(left, label, small));
        List<String[]> right = new ArrayList<>();
        right.add(new String[]{inv.getInvoiceType() == Invoice.Type.DEBIT_NOTE ? "Issued to (supplier)" : "Bill to (recipient)", (String) party.get("name")});
        if (party.get("address") != null) right.add(new String[]{"Address", (String) party.get("address")});
        right.add(new String[]{"GSTIN", party.get("gstin") != null ? (String) party.get("gstin") : "Unregistered"});
        if (party.get("state_code") != null) right.add(new String[]{"State", party.get("state_code") + " - " + state((String) party.get("state_code"))});
        meta.addCell(keyValueCell(right, label, small));
        doc.add(meta);

        // Lines.
        boolean intra = inv.getSupplyType() == Invoice.SupplyType.INTRA;
        String[] heads = intra
                ? new String[]{"#", "Description", "HSN/SAC", "Qty", "Rate", "Taxable value", "GST %", "CGST", "SGST", "Amount"}
                : new String[]{"#", "Description", "HSN/SAC", "Qty", "Rate", "Taxable value", "GST %", "IGST", "Amount"};
        float[] widths = intra ? new float[]{3, 26, 9, 6, 10, 12, 6, 10, 10, 12} : new float[]{3, 30, 9, 6, 11, 13, 6, 11, 13};
        PdfPTable lines = new PdfPTable(widths);
        lines.setWidthPercentage(100);
        lines.setSpacingBefore(8);
        lines.setHeaderRows(1);
        for (int i = 0; i < heads.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(heads[i], FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, Color.WHITE)));
            c.setBackgroundColor(ReportRenderer.PRIMARY);
            c.setPadding(4);
            c.setBorderColor(LINE);
            c.setHorizontalAlignment(i >= 3 ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            lines.addCell(c);
        }
        for (Invoice.Line l : inv.getLines()) {
            BigDecimal amount = l.getTaxableValue().add(l.getCgst()).add(l.getSgst()).add(l.getIgst());
            List<String> cells = new ArrayList<>(List.of(String.valueOf(l.getLineNo()), l.getDescription(),
                    l.getHsnSac() == null ? "" : l.getHsnSac(), l.getQuantity().stripTrailingZeros().toPlainString(),
                    ReportDocument.inr(Money.str(l.getUnitPrice())), ReportDocument.inr(Money.str(l.getTaxableValue())),
                    l.getGstRate().stripTrailingZeros().toPlainString()));
            if (intra) {
                cells.add(ReportDocument.inr(Money.str(l.getCgst())));
                cells.add(ReportDocument.inr(Money.str(l.getSgst())));
            } else {
                cells.add(ReportDocument.inr(Money.str(l.getIgst())));
            }
            cells.add(ReportDocument.inr(Money.str(amount)));
            for (int i = 0; i < cells.size(); i++) {
                PdfPCell c = new PdfPCell(new Phrase(cells.get(i), small));
                c.setPadding(3.5f);
                c.setBorderColor(LINE);
                c.setHorizontalAlignment(i >= 3 ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                lines.addCell(c);
            }
        }
        doc.add(lines);

        // Totals and amount in words.
        PdfPTable foot = new PdfPTable(new float[]{60, 40});
        foot.setWidthPercentage(100);
        foot.setSpacingBefore(6);
        PdfPCell words = new PdfPCell();
        words.setBorderColor(LINE);
        words.setPadding(6);
        words.addElement(new Paragraph("Amount in words", label));
        words.addElement(new Paragraph(AmountInWords.rupees(inv.getGrandTotal()), smallBold));
        if (inv.isReverseCharge()) {
            words.addElement(new Paragraph("Tax shown is payable by the recipient under reverse charge.", tiny));
        }
        if (inv.getNotes() != null && !inv.getNotes().isBlank()) {
            words.addElement(new Paragraph("Notes", label));
            words.addElement(new Paragraph(inv.getNotes(), small));
        }
        foot.addCell(words);
        List<String[]> totals = new ArrayList<>();
        totals.add(new String[]{"Taxable value", ReportDocument.inr(Money.str(inv.getTaxableTotal()))});
        if (intra) {
            totals.add(new String[]{"CGST", ReportDocument.inr(Money.str(inv.getCgstTotal()))});
            totals.add(new String[]{"SGST / UTGST", ReportDocument.inr(Money.str(inv.getSgstTotal()))});
        } else {
            totals.add(new String[]{"IGST", ReportDocument.inr(Money.str(inv.getIgstTotal()))});
        }
        totals.add(new String[]{"Total (Rs.)", ReportDocument.inr(Money.str(inv.getGrandTotal()))});
        PdfPTable tt = new PdfPTable(new float[]{55, 45});
        for (int i = 0; i < totals.size(); i++) {
            boolean last = i == totals.size() - 1;
            PdfPCell k = new PdfPCell(new Phrase(totals.get(i)[0], last ? smallBold : small));
            PdfPCell v = new PdfPCell(new Phrase(totals.get(i)[1], last ? smallBold : small));
            for (PdfPCell c : new PdfPCell[]{k, v}) {
                c.setBorder(last ? Rectangle.TOP : Rectangle.NO_BORDER);
                c.setBorderColor(ReportRenderer.PRIMARY);
                c.setPadding(3);
                if (last) c.setBackgroundColor(ReportRenderer.MINT);
            }
            v.setHorizontalAlignment(Element.ALIGN_RIGHT);
            tt.addCell(k);
            tt.addCell(v);
        }
        PdfPCell tc = new PdfPCell(tt);
        tc.setBorderColor(LINE);
        tc.setPadding(4);
        foot.addCell(tc);
        doc.add(foot);

        // Signatory.
        Paragraph sig = new Paragraph("For " + legalName, smallBold);
        sig.setAlignment(Element.ALIGN_RIGHT);
        sig.setSpacingBefore(22);
        doc.add(sig);
        Paragraph auth = new Paragraph("\n\nAuthorised Signatory", small);
        auth.setAlignment(Element.ALIGN_RIGHT);
        doc.add(auth);
        Paragraph gen = new Paragraph("This is a computer-generated document. Prepared with " + settings.letterhead().name() + ".", tiny);
        gen.setSpacingBefore(14);
        doc.add(gen);

        if (inv.getStatus() != Invoice.Status.POSTED) {
            watermark(writer, inv.getStatus() == Invoice.Status.CANCELLED ? "CANCELLED" : "DRAFT — NOT A TAX INVOICE");
        }
        doc.close();
        return out.toByteArray();
    }

    private static void watermark(PdfWriter writer, String text) {
        PdfContentByte under = writer.getDirectContentUnder();
        PdfGState gs = new PdfGState();
        gs.setFillOpacity(0.12f);
        under.saveState();
        under.setGState(gs);
        Phrase p = new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 44, new Color(0xB4, 0x23, 0x18)));
        ColumnText.showTextAligned(under, Element.ALIGN_CENTER, p, PageSize.A4.getWidth() / 2, PageSize.A4.getHeight() / 2, 45);
        under.restoreState();
    }

    private static PdfPCell keyValueCell(List<String[]> rows, Font label, Font value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(LINE);
        cell.setPadding(6);
        for (String[] r : rows) {
            Paragraph p = new Paragraph();
            p.add(new Chunk(r[0] + ": ", label));
            p.add(new Chunk(r[1] == null ? "" : r[1], value));
            cell.addElement(p);
        }
        return cell;
    }

    private static void center(Document doc, String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setAlignment(Element.ALIGN_CENTER);
        doc.add(p);
    }

    private static String state(String code) {
        String s = IndianStates.CODES.get(code);
        return s == null ? code : s;
    }

    private static String address(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return null;
        Map<String, Object> a = rows.get(0);
        return ReportRenderer.joinNonBlank(", ", (String) a.get("line1"), (String) a.get("line2"), (String) a.get("city"),
                state((String) a.get("state_code")), (String) a.get("pincode"));
    }
}
