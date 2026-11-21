package com.taxpilot.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import com.taxpilot.ApiTestSupport;

import tools.jackson.databind.JsonNode;

class AccountingIT extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    TestUser accountant;
    TestUser partner;
    String clientId;
    Map<String, String> acct; // account code -> id

    @BeforeEach
    void setUp() throws Exception {
        accountant = createUser("ACCOUNTANT");
        partner = createUser("PARTNER");
        clientId = activeClient();
        JsonNode chart = read(mvc.perform(authed(post("/api/v1/accounting/chart-of-accounts/apply-template"),
                accountant.token()).content(body(Map.of("clientId", clientId)))).andExpect(status().isOk()).andReturn());
        acct = new HashMap<>();
        chart.forEach(a -> acct.put(a.get("code").asString(), a.get("id").asString()));
        mvc.perform(authed(post("/api/v1/accounting/financial-years"), accountant.token())
                .content(body(Map.of("clientId", clientId, "financialYear", "2025-26")))).andExpect(status().isCreated());
    }

    /** A client taken through onboarding: accountant prepares, a second partner activates. */
    private String activeClient() throws Exception {
        TestUser approver = createUser("PARTNER");
        Map<String, Object> b = new HashMap<>(clientBody(randomPan('C'), "PRIVATE_LIMITED"));
        b.put("accountantId", accountant.id());
        b.put("reviewerId", partner.id());
        String id = read(mvc.perform(authed(post("/api/v1/clients"), accountant.token()).content(body(b)))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        for (String s : List.of("UNDER_VERIFICATION", "READY_FOR_APPROVAL")) {
            mvc.perform(authed(post("/api/v1/clients/" + id + "/status"), accountant.token())
                    .content(body(Map.of("targetStatus", s)))).andExpect(status().isOk());
        }
        mvc.perform(authed(post("/api/v1/clients/" + id + "/status"), approver.token())
                .content(body(Map.of("targetStatus", "ACTIVE")))).andExpect(status().isOk());
        return id;
    }

    private Map<String, Object> journal(String date, String narration, Object[]... lines) {
        List<Map<String, Object>> ls = new ArrayList<>();
        for (Object[] l : lines) {
            Map<String, Object> m = new HashMap<>();
            m.put("accountId", acct.get((String) l[0]));
            m.put("debit", l[1]);
            m.put("credit", l[2]);
            ls.add(m);
        }
        return new HashMap<>(Map.of("clientId", clientId, "entryDate", date, "narration", narration, "lines", ls));
    }

    private JsonNode createDraft(String token, Map<String, Object> body) throws Exception {
        return read(mvc.perform(authed(post("/api/v1/accounting/journals"), token).content(body(body)))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode action(String token, String id, String verb, Object body) throws Exception {
        var req = authed(post("/api/v1/accounting/journals/" + id + "/" + verb), token);
        if (body != null) req = req.content(body(body));
        return read(mvc.perform(req).andExpect(status().isOk()).andReturn());
    }

    /** Accountant prepares and submits; partner approves and posts. */
    private JsonNode postJournal(Map<String, Object> body) throws Exception {
        String id = createDraft(accountant.token(), body).get("id").asString();
        action(accountant.token(), id, "submit", null);
        action(partner.token(), id, "approve", null);
        return action(partner.token(), id, "post", null);
    }

    @Test
    void templateAndFinancialYearSetUp() throws Exception {
        assertThat(acct).containsKeys("1121", "2121", "3100", "4110", "5510");
        mvc.perform(authed(post("/api/v1/accounting/chart-of-accounts/apply-template"), accountant.token())
                .content(body(Map.of("clientId", clientId)))).andExpect(status().isConflict());
        JsonNode periods = getJson("/api/v1/accounting/periods?clientId=" + clientId, accountant.token());
        assertThat(periods.size()).isEqualTo(12);
        assertThat(periods.get(0).get("startDate").asString()).isEqualTo("2025-04-01");
        assertThat(periods.get(11).get("endDate").asString()).isEqualTo("2026-03-31");
        mvc.perform(authed(post("/api/v1/accounting/financial-years"), accountant.token())
                .content(body(Map.of("clientId", clientId, "financialYear", "2025-26")))).andExpect(status().isConflict());
    }

    @Test
    void fullLifecycleWithMakerCheckerAndIdempotency() throws Exception {
        Map<String, Object> capital = journal("2025-04-01", "Capital introduced",
                new Object[]{"1121", "100000.00", null}, new Object[]{"3100", null, "100000.00"});

        MvcResult first = mvc.perform(authed(post("/api/v1/accounting/journals"), accountant.token())
                .header("Idempotency-Key", "capital-intro-0001").content(body(capital)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn();
        String id = read(first).get("id").asString();
        mvc.perform(authed(post("/api/v1/accounting/journals"), accountant.token())
                        .header("Idempotency-Key", "capital-intro-0001").content(body(capital)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        action(accountant.token(), id, "submit", null);
        // Accountants cannot approve at all; the preparer could not either.
        mvc.perform(authed(post("/api/v1/accounting/journals/" + id + "/approve"), accountant.token()))
                .andExpect(status().isForbidden());
        // Approved but not yet posted: no effect on the trial balance.
        action(partner.token(), id, "approve", Map.of("reason", "Matches bank credit advice"));
        JsonNode tb = getJson("/api/v1/accounting/trial-balance?clientId=" + clientId + "&asOf=2026-03-31", partner.token());
        assertThat(tb.get("rows").size()).isZero();

        JsonNode posted = action(partner.token(), id, "post", null);
        assertThat(posted.get("status").asString()).isEqualTo("POSTED");
        assertThat(posted.get("entryNumber").asString()).isEqualTo("JV/2025-26/00001");
        // Posting again is a harmless no-op.
        assertThat(action(partner.token(), id, "post", null).get("entryNumber").asString()).isEqualTo("JV/2025-26/00001");

        Map<String, Object> edit = new HashMap<>(capital);
        edit.put("version", posted.get("version").asLong());
        mvc.perform(authed(put("/api/v1/accounting/journals/" + id), accountant.token()).content(body(edit)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        JsonNode second = postJournal(journal("2025-04-02", "Bank charges",
                new Object[]{"5320", "118.00", null}, new Object[]{"1121", null, "118.00"}));
        assertThat(second.get("entryNumber").asString()).isEqualTo("JV/2025-26/00002");
    }

    @Test
    void preparerCannotApproveOwnJournal() throws Exception {
        String id = createDraft(partner.token(), journal("2025-04-05", "Rent",
                new Object[]{"5510", "25000", null}, new Object[]{"1121", null, "25000"})).get("id").asString();
        action(partner.token(), id, "submit", null);
        mvc.perform(authed(post("/api/v1/accounting/journals/" + id + "/approve"), partner.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAKER_CHECKER"));
        JsonNode detail = getJson("/api/v1/accounting/journals/" + id, partner.token());
        assertThat(detail.get("allowedActions").valueStream().map(JsonNode::asString)).doesNotContain("APPROVE");
    }

    @Test
    void rejectsInvalidLines() throws Exception {
        // Group account
        mvc.perform(authed(post("/api/v1/accounting/journals"), accountant.token()).content(body(journal("2025-04-01", "x",
                        new Object[]{"1120", "10", null}, new Object[]{"3100", null, "10"}))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines[0].accountId"));
        // Both sides on one line
        mvc.perform(authed(post("/api/v1/accounting/journals"), accountant.token()).content(body(journal("2025-04-01", "x",
                        new Object[]{"1121", "10", "10"}, new Object[]{"3100", null, "10"}))))
                .andExpect(status().isBadRequest());
        // Three decimals
        mvc.perform(authed(post("/api/v1/accounting/journals"), accountant.token()).content(body(journal("2025-04-01", "x",
                        new Object[]{"1121", "10.005", null}, new Object[]{"3100", null, "10.005"}))))
                .andExpect(status().isBadRequest());
        // Date outside any open financial year
        mvc.perform(authed(post("/api/v1/accounting/journals"), accountant.token()).content(body(journal("2024-04-01", "x",
                        new Object[]{"1121", "10", null}, new Object[]{"3100", null, "10"}))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("entryDate"));
        // Unbalanced drafts may be saved but not submitted
        String id = createDraft(accountant.token(), journal("2025-04-01", "Unbalanced",
                new Object[]{"1121", "10", null}, new Object[]{"3100", null, "9"})).get("id").asString();
        mvc.perform(authed(post("/api/v1/accounting/journals/" + id + "/submit"), accountant.token()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNBALANCED"));
    }

    @Test
    void databaseRefusesToAlterPostedEntries() throws Exception {
        String id = postJournal(journal("2025-04-01", "Capital",
                new Object[]{"1121", "500", null}, new Object[]{"3100", null, "500"})).get("id").asString();
        assertThatThrownBy(() -> jdbc.update("update journal_entries set total_debit = 1 where id = ?::uuid", id))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update journal_entry_lines set debit = 1 where journal_entry_id = ?::uuid", id))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from journal_entries where id = ?::uuid", id))
                .hasMessageContaining("cannot be deleted");
        // Even a direct SQL attempt to post an unbalanced entry is refused.
        String draft = createDraft(accountant.token(), journal("2025-04-01", "Bad",
                new Object[]{"1121", "10", null}, new Object[]{"3100", null, "9"})).get("id").asString();
        assertThatThrownBy(() -> jdbc.update(
                "update journal_entries set status = 'POSTED', entry_number = 'X' where id = ?::uuid", draft))
                .hasMessageContaining("not balanced");
    }

    @Test
    void statementsMatchHandComputedFigures() throws Exception {
        postJournal(journal("2025-04-01", "Capital introduced",
                new Object[]{"1121", "100000.00", null}, new Object[]{"3100", null, "100000.00"}));
        postJournal(journal("2025-04-10", "Sales invoice INV-1 (intra-state, 18%)",
                new Object[]{"1130", "59000.00", null}, new Object[]{"4110", null, "50000.00"},
                new Object[]{"2121", null, "4500.00"}, new Object[]{"2122", null, "4500.00"}));
        postJournal(journal("2025-05-01", "Office rent for April",
                new Object[]{"5510", "20000.00", null}, new Object[]{"1121", null, "20000.00"}));
        // A draft must not affect anything.
        createDraft(accountant.token(), journal("2025-05-02", "Draft only",
                new Object[]{"5510", "999.00", null}, new Object[]{"1121", null, "999.00"}));

        JsonNode tb = getJson("/api/v1/accounting/trial-balance?clientId=" + clientId + "&asOf=2026-03-31", accountant.token());
        assertThat(tb.get("totalDebit").asString()).isEqualTo("159000.00");
        assertThat(tb.get("totalCredit").asString()).isEqualTo("159000.00");
        assertThat(tb.get("balanced").asBoolean()).isTrue();

        JsonNode pl = getJson("/api/v1/accounting/profit-loss?clientId=" + clientId + "&from=2025-04-01&to=2026-03-31",
                accountant.token());
        assertThat(pl.get("totalIncome").asString()).isEqualTo("50000.00");
        assertThat(pl.get("totalExpenses").asString()).isEqualTo("20000.00");
        assertThat(pl.get("netProfit").asString()).isEqualTo("30000.00");
        // April only: the rent was booked in May.
        JsonNode april = getJson("/api/v1/accounting/profit-loss?clientId=" + clientId + "&from=2025-04-01&to=2025-04-30",
                accountant.token());
        assertThat(april.get("netProfit").asString()).isEqualTo("50000.00");

        JsonNode bs = getJson("/api/v1/accounting/balance-sheet?clientId=" + clientId + "&asOf=2026-03-31", accountant.token());
        assertThat(bs.get("totalAssets").asString()).isEqualTo("139000.00");
        assertThat(bs.get("totalLiabilities").asString()).isEqualTo("9000.00");
        assertThat(bs.get("profitForPeriod").asString()).isEqualTo("30000.00");
        assertThat(bs.get("totalLiabilitiesAndEquity").asString()).isEqualTo("139000.00");
        assertThat(bs.get("balanced").asBoolean()).isTrue();

        JsonNode gl = getJson("/api/v1/accounting/ledger?clientId=" + clientId + "&accountId=" + acct.get("1121")
                + "&from=2025-04-05&to=2026-03-31", accountant.token());
        assertThat(gl.get("openingBalance").asString()).isEqualTo("100000.00");
        assertThat(gl.get("openingSide").asString()).isEqualTo("Dr");
        assertThat(gl.get("lines").size()).isEqualTo(1);
        assertThat(gl.get("closingBalance").asString()).isEqualTo("80000.00");
        assertThat(gl.get("closingSide").asString()).isEqualTo("Dr");
    }

    @Test
    void reversalUndoesTheEffectAndCanOnlyHappenOnce() throws Exception {
        postJournal(journal("2025-04-01", "Capital",
                new Object[]{"1121", "100000", null}, new Object[]{"3100", null, "100000"}));
        String rentId = postJournal(journal("2025-04-15", "Rent booked twice by mistake",
                new Object[]{"5510", "20000", null}, new Object[]{"1121", null, "20000"})).get("id").asString();

        JsonNode rev = action(accountant.token(), rentId, "reverse",
                Map.of("reversalDate", "2025-04-20", "reason", "Duplicate booking"));
        assertThat(rev.get("entryType").asString()).isEqualTo("REVERSAL");
        assertThat(rev.get("status").asString()).isEqualTo("DRAFT");
        assertThat(rev.get("lines").get(0).get("credit").asString()).isEqualTo("20000.00");
        mvc.perform(authed(post("/api/v1/accounting/journals/" + rentId + "/reverse"), accountant.token())
                        .content(body(Map.of("reversalDate", "2025-04-20", "reason", "again"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_REVERSED"));

        String revId = rev.get("id").asString();
        action(accountant.token(), revId, "submit", null);
        action(partner.token(), revId, "approve", null);
        action(partner.token(), revId, "post", null);

        JsonNode original = getJson("/api/v1/accounting/journals/" + rentId, accountant.token());
        assertThat(original.get("reversedById").asString()).isEqualTo(revId);
        assertThat(original.get("allowedActions").valueStream().map(JsonNode::asString)).doesNotContain("REVERSE");
        JsonNode pl = getJson("/api/v1/accounting/profit-loss?clientId=" + clientId + "&from=2025-04-01&to=2026-03-31",
                accountant.token());
        assertThat(pl.get("totalExpenses").asString()).isEqualTo("0.00");
    }

    @Test
    void closedPeriodsBlockPostingUntilReopened() throws Exception {
        JsonNode periods = getJson("/api/v1/accounting/periods?clientId=" + clientId, partner.token());
        String april = periods.get(0).get("id").asString();
        String pending = createDraft(accountant.token(), journal("2025-04-10", "Pending",
                new Object[]{"1121", "10", null}, new Object[]{"3100", null, "10"})).get("id").asString();
        action(accountant.token(), pending, "submit", null);

        mvc.perform(authed(post("/api/v1/accounting/periods/" + april + "/close"), partner.token()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNPOSTED_JOURNALS"));
        action(accountant.token(), pending, "cancel", null);
        mvc.perform(authed(post("/api/v1/accounting/periods/" + april + "/close"), accountant.token()))
                .andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/v1/accounting/periods/" + april + "/close"), partner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        mvc.perform(authed(post("/api/v1/accounting/journals"), accountant.token()).content(body(journal("2025-04-11", "Late",
                        new Object[]{"1121", "10", null}, new Object[]{"3100", null, "10"}))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("entryDate"));

        mvc.perform(authed(post("/api/v1/accounting/periods/" + april + "/reopen"), partner.token()).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(post("/api/v1/accounting/periods/" + april + "/reopen"), partner.token())
                        .content(body(Map.of("reason", "Late supplier invoice"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        postJournal(journal("2025-04-11", "Late",
                new Object[]{"1121", "10", null}, new Object[]{"3100", null, "10"}));

        JsonNode audit = getJson("/api/v1/audit-logs?clientId=" + clientId + "&size=100", partner.token());
        assertThat(audit.get("items").valueStream().map(n -> n.get("action").asString()))
                .contains("PERIOD_CLOSED", "PERIOD_REOPENED", "JOURNAL_POSTED", "COA_TEMPLATE_APPLIED");
    }

    @Test
    void journalsRequireAnActiveClient() throws Exception {
        String draftClient = read(mvc.perform(authed(post("/api/v1/clients"), accountant.token())
                .content(body(clientBody(randomPan('C'), "PRIVATE_LIMITED")))).andReturn()).get("id").asString();
        mvc.perform(authed(post("/api/v1/accounting/chart-of-accounts/apply-template"), accountant.token())
                .content(body(Map.of("clientId", draftClient)))).andExpect(status().isOk());
        mvc.perform(authed(post("/api/v1/accounting/financial-years"), accountant.token())
                .content(body(Map.of("clientId", draftClient, "financialYear", "2025-26")))).andExpect(status().isCreated());
        JsonNode chart = getJson("/api/v1/accounting/chart-of-accounts?clientId=" + draftClient, accountant.token());
        String bank = chart.valueStream().filter(a -> a.get("code").asString().equals("1121")).findFirst().orElseThrow()
                .get("id").asString();
        String cap = chart.valueStream().filter(a -> a.get("code").asString().equals("3100")).findFirst().orElseThrow()
                .get("id").asString();
        mvc.perform(authed(post("/api/v1/accounting/journals"), accountant.token()).content(body(Map.of(
                        "clientId", draftClient, "entryDate", "2025-04-01", "narration", "x",
                        "lines", List.of(Map.of("accountId", bank, "debit", "1"), Map.of("accountId", cap, "credit", "1"))))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIENT_NOT_ACTIVE"));
    }

    @Test
    void viewersCanReadButNotWrite() throws Exception {
        TestUser viewer = createUser("VIEWER");
        mvc.perform(authed(get("/api/v1/accounting/chart-of-accounts?clientId=" + clientId), viewer.token()))
                .andExpect(status().isOk());
        mvc.perform(authed(post("/api/v1/accounting/journals"), viewer.token()).content(body(journal("2025-04-01", "x",
                new Object[]{"1121", "10", null}, new Object[]{"3100", null, "10"})))).andExpect(status().isForbidden());
    }
}
