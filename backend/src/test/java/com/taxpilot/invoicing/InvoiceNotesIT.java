package com.taxpilot.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.taxpilot.AccountingTestSupport;

import tools.jackson.databind.JsonNode;

/** Credit and debit notes, ageing, registers and the printable tax invoice. */
class InvoiceNotesIT extends AccountingTestSupport {

    private String party(String type, String name) throws Exception {
        return read(mvc.perform(authed(post("/api/v1/parties"), accountant.token()).content(body(Map.of("clientId", clientId,
                "partyType", type, "name", name, "creditDays", 15, "gstin", gstin("29", randomPan('C'))))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private Map<String, Object> doc(String type, String partyId, String date, String qty, String price) {
        List<Map<String, Object>> ls = new ArrayList<>();
        String account = type.equals("SALES") || type.equals("CREDIT_NOTE") ? "4110" : "5530";
        ls.add(Map.of("description", "Cotton fabric", "hsnSac", "5208", "accountId", acct.get(account), "quantity", qty,
                "unitPrice", price, "gstRate", "18"));
        return new HashMap<>(Map.of("clientId", clientId, "invoiceType", type, "partyId", partyId, "invoiceDate", date,
                "gstRegistrationId", gstRegistrationId, "lines", ls));
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        return read(mvc.perform(authed(post("/api/v1/invoices"), accountant.token()).content(body(body)))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode postDoc(String id) throws Exception {
        mvc.perform(authed(post("/api/v1/invoices/" + id + "/submit"), accountant.token())).andExpect(status().isOk());
        return read(mvc.perform(authed(post("/api/v1/invoices/" + id + "/approve"), partner.token()))
                .andExpect(status().isOk()).andReturn());
    }

    private static String line(JsonNode journal, String code, String side) {
        for (JsonNode l : journal.get("lines")) {
            if (l.get("accountCode").asString().equals(code)) return l.get(side).asString();
        }
        return null;
    }

    @Test
    void creditNoteReversesPartOfASalesInvoiceWithinItsValue() throws Exception {
        String customer = party("CUSTOMER", "Mehta Garments");
        JsonNode inv = postDoc(create(doc("SALES", customer, "2026-04-15", "10", "1000")).get("id").asString());
        assertThat(inv.get("grandTotal").asString()).isEqualTo("11800.00");
        assertThat(inv.get("allowedActions").valueStream().map(JsonNode::asString)).contains("CREDIT_NOTE");

        Map<String, Object> cn = doc("CREDIT_NOTE", customer, "2026-04-20", "2", "1000");
        cn.put("originalInvoiceId", inv.get("id").asString());
        mvc.perform(authed(post("/api/v1/invoices"), accountant.token()).content(body(cn)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("noteReason"));
        cn.put("noteReason", "Two rolls returned (damaged)");
        JsonNode note = postDoc(create(cn).get("id").asString());
        assertThat(note.get("invoiceNumber").asString()).isEqualTo("CN/26-27/00001");
        assertThat(note.get("originalNumber").asString()).isEqualTo(inv.get("invoiceNumber").asString());
        assertThat(note.get("grandTotal").asString()).isEqualTo("2360.00");
        JsonNode j = getJson("/api/v1/accounting/journals/" + note.get("journalEntryId").asString(), partner.token());
        assertThat(line(j, "4110", "debit")).isEqualTo("2000.00");
        assertThat(line(j, "2121", "debit")).isEqualTo("180.00");
        assertThat(line(j, "2122", "debit")).isEqualTo("180.00");
        assertThat(line(j, "1130", "credit")).isEqualTo("2360.00");

        JsonNode original = getJson("/api/v1/invoices/" + inv.get("id").asString(), partner.token());
        assertThat(original.get("noteableBalance").asString()).isEqualTo("9440.00");
        Map<String, Object> tooMuch = doc("CREDIT_NOTE", customer, "2026-04-21", "9", "1000");
        tooMuch.put("originalInvoiceId", inv.get("id").asString());
        tooMuch.put("noteReason", "Everything returned");
        mvc.perform(authed(post("/api/v1/invoices"), accountant.token()).content(body(tooMuch)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines"));

        JsonNode register = getJson("/api/v1/accounting/registers?clientId=" + clientId + "&kind=SALES&from=2026-04-01&to=2026-04-30",
                partner.token());
        assertThat(register.get("rows").size()).isEqualTo(2);
        assertThat(register.get("taxable").asString()).isEqualTo("8000.00");
        assertThat(register.get("total").asString()).isEqualTo("9440.00");

        // Due 30-Apr (15 credit days); as at 31-May it is 31 days overdue.
        JsonNode ageing = getJson("/api/v1/accounting/ageing?clientId=" + clientId + "&kind=RECEIVABLE&asOf=2026-05-31", partner.token());
        JsonNode row = ageing.get("rows").get(0);
        assertThat(row.get("partyName").asString()).isEqualTo("Mehta Garments");
        assertThat(row.get("outstanding").asString()).isEqualTo("9440.00");
        assertThat(row.get("buckets").get(2).asString()).isEqualTo("9440.00");
        assertThat(ageing.get("reconciles").asBoolean()).isTrue();
    }

    @Test
    void debitNoteReducesAPurchaseAndUsesItsOwnSeries() throws Exception {
        String vendor = party("VENDOR", "Yarn Suppliers");
        Map<String, Object> pb = doc("PURCHASE", vendor, "2026-05-02", "5", "1000");
        pb.put("supplierInvoiceNumber", "YS/101");
        JsonNode purchase = postDoc(create(pb).get("id").asString());
        Map<String, Object> dn = doc("DEBIT_NOTE", vendor, "2026-05-10", "1", "1000");
        dn.put("originalInvoiceId", purchase.get("id").asString());
        dn.put("noteReason", "Short supply");
        JsonNode note = postDoc(create(dn).get("id").asString());
        assertThat(note.get("invoiceNumber").asString()).isEqualTo("DN/26-27/00001");
        JsonNode j = getJson("/api/v1/accounting/journals/" + note.get("journalEntryId").asString(), partner.token());
        assertThat(line(j, "2110", "debit")).isEqualTo("1180.00");
        assertThat(line(j, "5530", "credit")).isEqualTo("1000.00");
        assertThat(line(j, "1141", "credit")).isEqualTo("90.00");

        JsonNode ageing = getJson("/api/v1/accounting/ageing?clientId=" + clientId + "&kind=PAYABLE&asOf=2026-05-10", partner.token());
        assertThat(ageing.get("total").asString()).isEqualTo("4720.00");
        assertThat(ageing.get("reconciles").asBoolean()).isTrue();
        JsonNode register = getJson("/api/v1/accounting/registers?clientId=" + clientId + "&kind=PURCHASE&from=2026-05-01&to=2026-05-31",
                partner.token());
        assertThat(register.get("total").asString()).isEqualTo("4720.00");

        // A credit note cannot point at a purchase.
        Map<String, Object> wrong = doc("CREDIT_NOTE", vendor, "2026-05-11", "1", "100");
        wrong.put("originalInvoiceId", purchase.get("id").asString());
        wrong.put("noteReason", "x");
        mvc.perform(authed(post("/api/v1/invoices"), accountant.token()).content(body(wrong))).andExpect(status().isBadRequest());
    }

    @Test
    void taxInvoicePdfIsAvailableForOutwardDocumentsOnly() throws Exception {
        String customer = party("CUSTOMER", "Print Customer");
        JsonNode draft = create(doc("SALES", customer, "2026-04-15", "1", "50000"));
        byte[] draftPdf = mvc.perform(authed(get("/api/v1/invoices/" + draft.get("id").asString() + "/pdf"), accountant.token()))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(draftPdf, 0, 5)).isEqualTo("%PDF-");
        JsonNode posted = postDoc(draft.get("id").asString());
        mvc.perform(authed(get("/api/v1/invoices/" + posted.get("id").asString() + "/pdf"), accountant.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("INV-26-27-00001.pdf")));

        String vendor = party("VENDOR", "Print Vendor");
        Map<String, Object> pb = doc("PURCHASE", vendor, "2026-04-16", "1", "100");
        pb.put("supplierInvoiceNumber", "PV-1");
        JsonNode purchase = create(pb);
        mvc.perform(authed(get("/api/v1/invoices/" + purchase.get("id").asString() + "/pdf"), accountant.token()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_OUR_DOCUMENT"));
    }
}
