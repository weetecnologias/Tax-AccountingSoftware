package com.taxpilot.accounting;

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
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.taxpilot.AccountingTestSupport;

import tools.jackson.databind.JsonNode;

/** Phase 2B part 2: cash flow, comparisons, year-end close, recurring journals and party lines. */
class AccountingPart2IT extends AccountingTestSupport {

    private String base() {
        return "/api/v1/accounting";
    }

    @Test
    void cashFlowReconcilesToCashMovementAndComparisonsWork() throws Exception {
        postJournal("2026-04-01", "Capital introduced", null, new Object[]{"1121", "500000.00", null}, new Object[]{"3100", null, "500000.00"});
        postJournal("2026-04-10", "Sales on credit", null, new Object[]{"1130", "118000.00", null}, new Object[]{"4110", null, "100000.00"},
                new Object[]{"2121", null, "9000.00"}, new Object[]{"2122", null, "9000.00"});
        postJournal("2026-05-05", "Part receipt from customer", null, new Object[]{"1121", "59000.00", null}, new Object[]{"1130", null, "59000.00"});
        postJournal("2026-05-10", "Rent for May", null, new Object[]{"5510", "20000.00", null}, new Object[]{"1121", null, "20000.00"});
        postJournal("2026-06-01", "Machinery purchased", null, new Object[]{"1210", "150000.00", null}, new Object[]{"1121", null, "150000.00"});
        postJournal("2026-06-15", "Term loan received", null, new Object[]{"1121", "200000.00", null}, new Object[]{"2210", null, "200000.00"});
        postJournal("2026-06-30", "Depreciation for the quarter", null, new Object[]{"5400", "10000.00", null}, new Object[]{"1220", null, "10000.00"});

        JsonNode cf = getJson(base() + "/cash-flow?clientId=" + clientId + "&from=2026-04-01&to=2027-03-31", accountant.token());
        assertThat(cf.get("netProfit").asString()).isEqualTo("70000.00");
        // 70,000 profit + 10,000 depreciation − 59,000 rise in receivables + 18,000 rise in GST payable.
        assertThat(cf.get("operatingTotal").asString()).isEqualTo("39000.00");
        assertThat(cf.get("investing").get("total").asString()).isEqualTo("-150000.00");
        assertThat(cf.get("financing").get("total").asString()).isEqualTo("700000.00");
        assertThat(cf.get("netChange").asString()).isEqualTo("589000.00");
        assertThat(cf.get("openingCash").asString()).isEqualTo("0.00");
        assertThat(cf.get("closingCash").asString()).isEqualTo("589000.00");
        assertThat(cf.get("reconciles").asBoolean()).isTrue();

        JsonNode pl = getJson(base() + "/profit-loss?clientId=" + clientId
                + "&from=2026-05-01&to=2026-05-31&compareFrom=2026-04-01&compareTo=2026-04-30", accountant.token());
        assertThat(pl.get("netProfit").asString()).isEqualTo("-20000.00");
        assertThat(pl.get("previousNetProfit").asString()).isEqualTo("100000.00");
        assertThat(pl.get("header").get("compareFrom").asString()).isEqualTo("2026-04-01");

        JsonNode bs = getJson(base() + "/balance-sheet?clientId=" + clientId + "&asOf=2026-06-30&compareAsOf=2026-04-30",
                accountant.token());
        assertThat(bs.get("balanced").asBoolean()).isTrue();
        assertThat(bs.get("previousTotalAssets").asString()).isEqualTo("618000.00");
        assertThat(bs.get("previousProfitForPeriod").asString()).isEqualTo("100000.00");
    }

    @Test
    void yearEndCloseTransfersProfitAndLocksTheYear() throws Exception {
        postJournal("2026-04-10", "Cash sales", null, new Object[]{"1121", "50000.00", null}, new Object[]{"4110", null, "50000.00"});
        postJournal("2026-05-10", "Rent", null, new Object[]{"5510", "20000.00", null}, new Object[]{"1121", null, "20000.00"});

        // The closing type cannot be created by hand.
        Map<String, Object> manual = journalBody("2027-03-31", "Manual close", line("4110", "1.00", null, null), line("3200", null, "1.00", null));
        manual.put("entryType", "CLOSING");
        mvc.perform(authed(post(base() + "/journals"), accountant.token()).content(body(manual)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("entryType"));

        JsonNode stale = prepareClose();
        assertThat(stale.get("entryType").asString()).isEqualTo("CLOSING");
        assertThat(stale.get("status").asString()).isEqualTo("DRAFT");
        assertThat(stale.get("entryDate").asString()).isEqualTo("2027-03-31");
        assertThat(amount(stale, "4110", "debit")).isEqualTo("50000.00");
        assertThat(amount(stale, "5510", "credit")).isEqualTo("20000.00");
        assertThat(amount(stale, "3200", "credit")).isEqualTo("30000.00");
        mvc.perform(authed(post(base() + "/financial-years/2026-27/prepare-close"), accountant.token())
                .content(body(Map.of("clientId", clientId)))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CLOSING"));

        // A late expense makes the prepared entry stale; posting it must be refused.
        postJournal("2026-06-10", "Late bill", null, new Object[]{"5510", "1000.00", null}, new Object[]{"1121", null, "1000.00"});
        String staleId = stale.get("id").asString();
        mvc.perform(authed(post(base() + "/journals/" + staleId + "/submit"), accountant.token())).andExpect(status().isOk());
        mvc.perform(authed(post(base() + "/journals/" + staleId + "/approve"), partner.token())).andExpect(status().isOk());
        mvc.perform(authed(post(base() + "/journals/" + staleId + "/post"), partner.token()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_CLOSING"));
        mvc.perform(authed(post(base() + "/journals/" + staleId + "/cancel"), accountant.token())).andExpect(status().isOk());

        JsonNode closing = prepareClose();
        assertThat(amount(closing, "3200", "credit")).isEqualTo("29000.00");
        JsonNode posted = approveJournal(closing.get("id").asString());
        assertThat(posted.get("status").asString()).isEqualTo("POSTED");
        assertThat(posted.get("allowedActions").valueStream().map(JsonNode::asString)).doesNotContain("REVERSE", "EDIT");

        JsonNode periods = getJson(base() + "/periods?clientId=" + clientId, partner.token());
        assertThat(periods.valueStream().filter(p -> p.get("financialYear").asString().equals("2026-27")))
                .allMatch(p -> p.get("status").asString().equals("CLOSED") && p.get("yearClosed").asBoolean());
        mvc.perform(authed(post(base() + "/periods/" + periods.get(0).get("id").asString() + "/reopen"), partner.token())
                .content(body(Map.of("reason", "Need to fix an entry")))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("YEAR_CLOSED"));

        // The P&L for the year is unaffected by the closing entry; the balance sheet carries it in retained earnings.
        JsonNode pl = getJson(base() + "/profit-loss?clientId=" + clientId + "&from=2026-04-01&to=2027-03-31", partner.token());
        assertThat(pl.get("netProfit").asString()).isEqualTo("29000.00");
        JsonNode bs = getJson(base() + "/balance-sheet?clientId=" + clientId + "&asOf=2027-03-31", partner.token());
        assertThat(bs.get("balanced").asBoolean()).isTrue();
        assertThat(bs.get("totalEquity").asString()).isEqualTo("29000.00");

        // Nothing more can be posted into the closed year.
        mvc.perform(authed(post(base() + "/journals"), accountant.token()).content(body(journalBody("2026-07-01", "Too late",
                line("5510", "5.00", null, null), line("1121", null, "5.00", null))))).andExpect(status().is4xxClientError());
    }

    @Test
    void recurringJournalsGenerateDraftsOnceAndSkipDatesWithoutAYear() throws Exception {
        Map<String, Object> rent = new HashMap<>(Map.of("clientId", clientId, "name", "Office rent", "narration", "Monthly office rent",
                "frequency", "MONTHLY", "nextDate", "2026-04-30",
                "lines", List.of(line("5510", "25000.00", null, null), line("1121", null, "25000.00", null))));
        JsonNode t = read(mvc.perform(authed(post(base() + "/recurring"), accountant.token()).content(body(rent)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(t.get("amount").asString()).isEqualTo("25000.00");
        Map<String, Object> old = new HashMap<>(rent);
        old.put("name", "Old subscription");
        old.put("nextDate", "2026-03-31");
        mvc.perform(authed(post(base() + "/recurring"), accountant.token()).content(body(old))).andExpect(status().isCreated());

        JsonNode gen = generate("2026-06-30");
        assertThat(gen.get("createdJournalIds").size()).isEqualTo(3);
        assertThat(gen.get("skipped").get(0).asString()).contains("Old subscription").contains("no financial year");
        JsonNode first = getJson(base() + "/journals/" + gen.get("createdJournalIds").get(0).asString(), accountant.token());
        assertThat(first.get("status").asString()).isEqualTo("DRAFT");
        assertThat(first.get("entryDate").asString()).isEqualTo("2026-04-30");

        // Running again for the same date creates nothing new.
        assertThat(generate("2026-06-30").get("createdJournalIds").size()).isZero();
        JsonNode list = getJson(base() + "/recurring?clientId=" + clientId, accountant.token());
        assertThat(list.valueStream().filter(x -> x.get("name").asString().equals("Office rent")).findFirst().orElseThrow()
                .get("nextDate").asString()).isEqualTo("2026-07-30");
    }

    @Test
    void partyLinesAreOnlyAllowedOnReceivableAndPayableAccounts() throws Exception {
        String customer = read(mvc.perform(authed(post("/api/v1/parties"), accountant.token()).content(body(Map.of(
                "clientId", clientId, "partyType", "CUSTOMER", "name", "Line Party", "stateCode", "29"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        mvc.perform(authed(post(base() + "/journals"), accountant.token()).content(body(journalBody("2026-04-05", "Wrong party line",
                        line("5510", "100.00", null, customer), line("1121", null, "100.00", null)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines[0].partyId"));
        mvc.perform(authed(post(base() + "/journals"), accountant.token()).content(body(journalBody("2026-04-05", "Unknown party",
                        line("1130", "100.00", null, UUID.randomUUID().toString()), line("4110", null, "100.00", null)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines[0].partyId"));
        JsonNode ok = read(mvc.perform(authed(post(base() + "/journals"), accountant.token()).content(body(journalBody("2026-04-05",
                        "Opening receivable", line("1130", "100.00", null, customer), line("4110", null, "100.00", null)))))
                .andExpect(status().isCreated()).andReturn());
        JsonNode posted = approveJournal(ok.get("id").asString());
        assertThat(posted.get("lines").get(0).get("partyName").asString()).isEqualTo("Line Party");

        JsonNode ageing = getJson(base() + "/ageing?clientId=" + clientId + "&kind=RECEIVABLE&asOf=2026-04-30", partner.token());
        assertThat(ageing.get("rows").get(0).get("partyName").asString()).isEqualTo("Line Party");
        assertThat(ageing.get("rows").get(0).get("unlinked").asString()).isEqualTo("100.00");
        assertThat(ageing.get("reconciles").asBoolean()).isTrue();
    }

    @Test
    void reportsExportToPdfAndExcelAndAreAudited() throws Exception {
        postJournal("2026-04-01", "Capital", null, new Object[]{"1121", "100000.00", null}, new Object[]{"3100", null, "100000.00"});
        postJournal("2026-04-02", "Sales", null, new Object[]{"1121", "1234567.89", null}, new Object[]{"4110", null, "1234567.89"});
        byte[] pdf = export("TRIAL_BALANCE", "pdf", "&asOf=2027-03-31", "application/pdf");
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        byte[] xlsx = export("PROFIT_LOSS", "xlsx", "&from=2026-04-01&to=2027-03-31&compareFrom=2025-04-01&compareTo=2026-03-31",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(new String(xlsx, 0, 2)).isEqualTo("PK");
        for (String r : List.of("BALANCE_SHEET", "AGEING_RECEIVABLE", "AGEING_PAYABLE")) {
            export(r, "pdf", "&asOf=2027-03-31", "application/pdf");
        }
        for (String r : List.of("CASH_FLOW", "SALES_REGISTER", "PURCHASE_REGISTER")) {
            export(r, "xlsx", "&from=2026-04-01&to=2027-03-31", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }
        export("LEDGER", "pdf", "&from=2026-04-01&to=2027-03-31&accountId=" + acct.get("1121"), "application/pdf");
        mvc.perform(authed(get(base() + "/export?report=LEDGER&format=pdf&clientId=" + clientId + "&from=2026-04-01&to=2027-03-31"),
                accountant.token())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
        JsonNode audit = getJson("/api/v1/audit-logs?action=REPORT_EXPORTED&entityId=" + clientId, admin());
        assertThat(audit.get("totalItems").asLong()).isGreaterThanOrEqualTo(9);
    }

    @Test
    void gstRateSlabsAreReviewedByATaxApprover() throws Exception {
        JsonNode slabs = getJson("/api/v1/settings/gst-rates", accountant.token());
        assertThat(slabs.size()).isGreaterThanOrEqualTo(10);
        String pending = slabs.valueStream().filter(s -> s.get("reviewStatus").asString().equals("PENDING_REVIEW"))
                .findFirst().orElseThrow().get("id").asString();
        mvc.perform(authed(post("/api/v1/settings/gst-rates/" + pending + "/approve"), accountant.token()))
                .andExpect(status().isForbidden());
        JsonNode approved = read(mvc.perform(authed(post("/api/v1/settings/gst-rates/" + pending + "/approve"), partner.token()))
                .andExpect(status().isOk()).andReturn());
        assertThat(approved.get("reviewStatus").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("reviewedBy").asString()).isEqualTo("Test PARTNER");
        assertThat(approved.get("reviewedAt").isNull()).isFalse();
        mvc.perform(authed(post("/api/v1/settings/gst-rates/" + pending + "/approve"), partner.token()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));
        JsonNode audit = getJson("/api/v1/audit-logs?action=GST_RATE_APPROVED&entityId=" + pending, admin());
        assertThat(audit.get("totalItems").asLong()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode prepareClose() throws Exception {
        return read(mvc.perform(authed(post(base() + "/financial-years/2026-27/prepare-close"), accountant.token())
                .content(body(Map.of("clientId", clientId)))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode generate(String asOf) throws Exception {
        return read(mvc.perform(authed(post(base() + "/recurring/generate"), accountant.token())
                .content(body(Map.of("clientId", clientId, "asOf", asOf)))).andExpect(status().isOk()).andReturn());
    }

    private byte[] export(String report, String format, String params, String contentType) throws Exception {
        return mvc.perform(authed(get(base() + "/export?report=" + report + "&format=" + format + "&clientId=" + clientId + params),
                        accountant.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", contentType))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private Map<String, Object> line(String code, String debit, String credit, String partyId) {
        Map<String, Object> m = new HashMap<>();
        m.put("accountId", acct.get(code));
        m.put("debit", debit);
        m.put("credit", credit);
        if (partyId != null) m.put("partyId", partyId);
        return m;
    }

    @SafeVarargs
    private Map<String, Object> journalBody(String date, String narration, Map<String, Object>... lines) {
        return new HashMap<>(Map.of("clientId", clientId, "entryDate", date, "narration", narration,
                "lines", new ArrayList<>(List.of(lines))));
    }

    private static String amount(JsonNode journal, String code, String side) {
        for (JsonNode l : journal.get("lines")) {
            if (l.get("accountCode").asString().equals(code)) return l.get(side).asString();
        }
        return null;
    }
}
