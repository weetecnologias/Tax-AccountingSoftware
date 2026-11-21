package com.taxpilot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;

import tools.jackson.databind.JsonNode;

/**
 * An ACTIVE client with a Karnataka GSTIN, the standard chart of accounts and FY 2026-27 open.
 * Accountant prepares; partner approves.
 */
public abstract class AccountingTestSupport extends ApiTestSupport {

    protected TestUser accountant;
    protected TestUser partner;
    protected String clientId;
    protected String clientPan;
    protected String gstRegistrationId;
    protected Map<String, String> acct;

    @BeforeEach
    void setUpBooks() throws Exception {
        accountant = createUser("ACCOUNTANT");
        partner = createUser("PARTNER");
        TestUser approver = createUser("PARTNER");
        clientPan = randomPan('C');
        Map<String, Object> b = new HashMap<>(clientBody(clientPan, "PRIVATE_LIMITED"));
        b.put("accountantId", accountant.id());
        b.put("reviewerId", partner.id());
        clientId = read(mvc.perform(authed(post("/api/v1/clients"), accountant.token()).content(body(b)))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        gstRegistrationId = read(mvc.perform(authed(post("/api/v1/clients/" + clientId + "/gst-registrations"),
                accountant.token()).content(body(Map.of("gstin", gstin("29", clientPan), "registrationType", "REGULAR",
                "effectiveDate", "2020-04-01", "filingFrequency", "MONTHLY")))).andExpect(status().isCreated())
                .andReturn()).get("id").asString();
        for (String s : List.of("UNDER_VERIFICATION", "READY_FOR_APPROVAL")) {
            mvc.perform(authed(post("/api/v1/clients/" + clientId + "/status"), accountant.token())
                    .content(body(Map.of("targetStatus", s)))).andExpect(status().isOk());
        }
        mvc.perform(authed(post("/api/v1/clients/" + clientId + "/status"), approver.token())
                .content(body(Map.of("targetStatus", "ACTIVE")))).andExpect(status().isOk());
        JsonNode chart = read(mvc.perform(authed(post("/api/v1/accounting/chart-of-accounts/apply-template"),
                accountant.token()).content(body(Map.of("clientId", clientId)))).andExpect(status().isOk()).andReturn());
        acct = new HashMap<>();
        chart.forEach(a -> acct.put(a.get("code").asString(), a.get("id").asString()));
        mvc.perform(authed(post("/api/v1/accounting/financial-years"), accountant.token())
                .content(body(Map.of("clientId", clientId, "financialYear", "2026-27")))).andExpect(status().isCreated());
    }

    protected static String gstin(String state, String pan) {
        String first14 = state + pan + "1Z";
        String cs = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int p = cs.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += p / 36 + p % 36;
        }
        return first14 + cs.charAt((36 - sum % 36) % 36);
    }

    /** Accountant prepares and submits a journal; partner approves and posts it. Returns the posted journal. */
    protected JsonNode postJournal(String date, String narration, String reference, Object[]... lines) throws Exception {
        List<Map<String, Object>> ls = new ArrayList<>();
        for (Object[] l : lines) {
            Map<String, Object> m = new HashMap<>();
            m.put("accountId", acct.get((String) l[0]));
            m.put("debit", l[1]);
            m.put("credit", l[2]);
            ls.add(m);
        }
        Map<String, Object> body = new HashMap<>(Map.of("clientId", clientId, "entryDate", date, "narration", narration,
                "lines", ls));
        if (reference != null) body.put("reference", reference);
        String id = read(mvc.perform(authed(post("/api/v1/accounting/journals"), accountant.token()).content(body(body)))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        return approveJournal(id);
    }

    protected JsonNode approveJournal(String id) throws Exception {
        mvc.perform(authed(post("/api/v1/accounting/journals/" + id + "/submit"), accountant.token())).andExpect(status().isOk());
        mvc.perform(authed(post("/api/v1/accounting/journals/" + id + "/approve"), partner.token())).andExpect(status().isOk());
        return read(mvc.perform(authed(post("/api/v1/accounting/journals/" + id + "/post"), partner.token()))
                .andExpect(status().isOk()).andReturn());
    }

    /** Toggles the organisation's self-approval policy (admin only). */
    protected void setSelfApproval(boolean allowed) throws Exception {
        JsonNode s = getJson("/api/v1/settings/organisation", admin());
        Map<String, Object> req = new HashMap<>();
        req.put("displayName", s.get("displayName").asString());
        req.put("tagline", s.get("tagline").isNull() ? null : s.get("tagline").asString());
        req.put("reportFooter", s.get("reportFooter").isNull() ? null : s.get("reportFooter").asString());
        req.put("allowSelfApproval", allowed);
        req.put("version", s.get("version").asLong());
        mvc.perform(authed(put("/api/v1/settings/organisation"), admin()).content(body(req))).andExpect(status().isOk());
    }
}
