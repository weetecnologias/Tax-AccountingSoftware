package com.taxpilot.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.taxpilot.AccountingTestSupport;

import tools.jackson.databind.JsonNode;

class InvoiceIT extends AccountingTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String party(String type, String name, String state, boolean registered) throws Exception {
        Map<String, Object> b = new HashMap<>(Map.of("clientId", clientId, "partyType", type, "name", name,
                "creditDays", 15));
        if (registered) b.put("gstin", gstin(state, randomPan('C')));
        else b.put("stateCode", state);
        return read(mvc.perform(authed(post("/api/v1/parties"), accountant.token()).content(body(b)))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private Map<String, Object> invoice(String type, String partyId, String date, Object[]... lines) {
        List<Map<String, Object>> ls = new java.util.ArrayList<>();
        for (Object[] l : lines) {
            ls.add(Map.of("description", l[0], "accountId", acct.get((String) l[1]), "quantity", l[2],
                    "unitPrice", l[3], "gstRate", l[4]));
        }
        Map<String, Object> b = new HashMap<>(Map.of("clientId", clientId, "invoiceType", type, "partyId", partyId,
                "invoiceDate", date, "gstRegistrationId", gstRegistrationId, "lines", ls));
        return b;
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        return read(mvc.perform(authed(post("/api/v1/invoices"), accountant.token()).content(body(body)))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode submitAndApprove(String id) throws Exception {
        mvc.perform(authed(post("/api/v1/invoices/" + id + "/submit"), accountant.token())).andExpect(status().isOk());
        return read(mvc.perform(authed(post("/api/v1/invoices/" + id + "/approve"), partner.token()))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode journalOf(JsonNode invoice) throws Exception {
        return getJson("/api/v1/accounting/journals/" + invoice.get("journalEntryId").asString(), partner.token());
    }

    private static String lineAmount(JsonNode journal, String code, String side) {
        for (JsonNode l : journal.get("lines")) {
            if (l.get("accountCode").asString().equals(code)) return l.get(side).asString();
        }
        return null;
    }

    @Test
    void intraStateSalesInvoicePostsAndIsNumbered() throws Exception {
        String customer = party("CUSTOMER", "Mehta Garments", "29", true);
        JsonNode draft = create(invoice("SALES", customer, "2026-04-15",
                new Object[]{"Cotton fabric, 100 m rolls", "4110", "10", "1000", "18"}));
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("supplyType").asString()).isEqualTo("INTRA");
        assertThat(draft.get("cgstTotal").asString()).isEqualTo("900.00");
        assertThat(draft.get("grandTotal").asString()).isEqualTo("11800.00");
        assertThat(draft.get("dueDate").asString()).isEqualTo("2026-04-30");
        assertThat(draft.get("invoiceNumber").isNull()).isTrue();

        JsonNode posted = submitAndApprove(draft.get("id").asString());
        assertThat(posted.get("status").asString()).isEqualTo("POSTED");
        assertThat(posted.get("invoiceNumber").asString()).isEqualTo("INV/26-27/00001");

        JsonNode j = journalOf(posted);
        assertThat(j.get("status").asString()).isEqualTo("POSTED");
        assertThat(lineAmount(j, "1130", "debit")).isEqualTo("11800.00");
        assertThat(lineAmount(j, "4110", "credit")).isEqualTo("10000.00");
        assertThat(lineAmount(j, "2121", "credit")).isEqualTo("900.00");
        assertThat(lineAmount(j, "2122", "credit")).isEqualTo("900.00");

        JsonNode second = submitAndApprove(create(invoice("SALES", customer, "2026-04-16",
                new Object[]{"Dyeing charges", "4120", "1", "500", "18"})).get("id").asString());
        assertThat(second.get("invoiceNumber").asString()).isEqualTo("INV/26-27/00002");

        JsonNode tb = getJson("/api/v1/accounting/trial-balance?clientId=" + clientId + "&asOf=2027-03-31", partner.token());
        assertThat(tb.get("balanced").asBoolean()).isTrue();
        assertThat(tb.get("totalDebit").asString()).isEqualTo("12390.00");
    }

    @Test
    void interStateSalesChargeIgst() throws Exception {
        String customer = party("CUSTOMER", "Pune Retail", "27", true);
        JsonNode inv = submitAndApprove(create(invoice("SALES", customer, "2026-05-02",
                new Object[]{"Sarees", "4110", "4", "2500", "5"})).get("id").asString());
        assertThat(inv.get("supplyType").asString()).isEqualTo("INTER");
        assertThat(inv.get("igstTotal").asString()).isEqualTo("500.00");
        assertThat(lineAmount(journalOf(inv), "2123", "credit")).isEqualTo("500.00");
    }

    @Test
    void purchaseInvoiceClaimsInputTaxAndRejectsDuplicates() throws Exception {
        String vendor = party("VENDOR", "Office Supplies Co", "29", true);
        Map<String, Object> body = invoice("PURCHASE", vendor, "2026-04-20",
                new Object[]{"Stationery", "5530", "1", "5000", "18"});
        body.put("supplierInvoiceNumber", "OSC/889");
        JsonNode inv = submitAndApprove(create(body).get("id").asString());
        JsonNode j = journalOf(inv);
        assertThat(lineAmount(j, "5530", "debit")).isEqualTo("5000.00");
        assertThat(lineAmount(j, "1141", "debit")).isEqualTo("450.00");
        assertThat(lineAmount(j, "1142", "debit")).isEqualTo("450.00");
        assertThat(lineAmount(j, "2110", "credit")).isEqualTo("5900.00");

        mvc.perform(authed(post("/api/v1/invoices"), accountant.token()).content(body(body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_INVOICE"));
    }

    @Test
    void reverseChargePurchaseCreditsRcmLiability() throws Exception {
        String advocate = party("VENDOR", "Legal Advisor (unregistered)", "29", false);
        Map<String, Object> body = invoice("PURCHASE", advocate, "2026-04-22",
                new Object[]{"Legal services", "5520", "1", "10000", "18"});
        body.put("supplierInvoiceNumber", "LA-12");
        body.put("reverseCharge", true);
        JsonNode j = journalOf(submitAndApprove(create(body).get("id").asString()));
        assertThat(lineAmount(j, "5520", "debit")).isEqualTo("10000.00");
        assertThat(lineAmount(j, "1141", "debit")).isEqualTo("900.00");
        assertThat(lineAmount(j, "2110", "credit")).isEqualTo("10000.00");
        assertThat(lineAmount(j, "2125", "credit")).isEqualTo("1800.00");
    }

    @Test
    void validatesRatesAccountsAndParties() throws Exception {
        String customer = party("CUSTOMER", "Valid Customer", "29", true);
        String vendor = party("VENDOR", "Vendor Only", "29", true);
        // 12% was merged into other slabs from 22-Sep-2025.
        mvc.perform(authed(post("/api/v1/invoices"), accountant.token()).content(body(invoice("SALES", customer,
                        "2026-04-15", new Object[]{"x", "4110", "1", "100", "12"}))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines[0].gstRate"));
        mvc.perform(authed(post("/api/v1/invoices"), accountant.token()).content(body(invoice("SALES", customer,
                        "2026-04-15", new Object[]{"x", "5530", "1", "100", "18"}))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines[0].accountId"));
        mvc.perform(authed(post("/api/v1/invoices"), accountant.token()).content(body(invoice("SALES", vendor,
                        "2026-04-15", new Object[]{"x", "4110", "1", "100", "18"}))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("partyId"));
        // 12% is still valid for a date before the change.
        JsonNode rates = getJson("/api/v1/invoices/gst-rates?date=2025-06-01", accountant.token());
        assertThat(rates.valueStream().map(r -> r.get("rate").asString())).contains("12.00");
    }

    @Test
    void makerCheckerAppliesUnlessSelfApprovalIsAllowed() throws Exception {
        String customer = party("CUSTOMER", "Self Approval Test", "29", true);
        String id = read(mvc.perform(authed(post("/api/v1/invoices"), partner.token()).content(body(invoice("SALES",
                customer, "2026-06-01", new Object[]{"x", "4110", "1", "100", "18"})))).andReturn()).get("id").asString();
        mvc.perform(authed(post("/api/v1/invoices/" + id + "/submit"), partner.token())).andExpect(status().isOk());
        mvc.perform(authed(post("/api/v1/invoices/" + id + "/approve"), accountant.token())).andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/v1/invoices/" + id + "/approve"), partner.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAKER_CHECKER"));
        try {
            setSelfApproval(true);
            JsonNode inv = read(mvc.perform(authed(post("/api/v1/invoices/" + id + "/approve"), partner.token()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(inv.get("selfApproved").asBoolean()).isTrue();
            JsonNode audit = getJson("/api/v1/audit-logs?entityId=" + id, admin());
            assertThat(audit.get("items").valueStream().anyMatch(a -> a.get("action").asString().equals("INVOICE_POSTED")
                    && a.get("details").has("selfApproved"))).isTrue();
            JsonNode policy = getJson("/api/v1/audit-logs?action=APPROVAL_POLICY_CHANGED", admin());
            assertThat(policy.get("totalItems").asLong()).isGreaterThan(0);
        } finally {
            setSelfApproval(false);
        }
    }

    @Test
    void postedInvoicesAreImmutable() throws Exception {
        String customer = party("CUSTOMER", "Immutable Test", "29", true);
        String id = submitAndApprove(create(invoice("SALES", customer, "2026-04-15",
                new Object[]{"x", "4110", "1", "100", "18"})).get("id").asString()).get("id").asString();
        assertThatThrownBy(() -> jdbc.update("update invoices set grand_total = 1 where id = ?::uuid", id))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from invoice_lines where invoice_id = ?::uuid", id))
                .hasMessageContaining("immutable");
    }
}
