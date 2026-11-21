package com.taxpilot.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.taxpilot.ApiTestSupport;
import com.taxpilot.common.validation.IndianIds;

import tools.jackson.databind.JsonNode;

class ClientIT extends ApiTestSupport {

    private String createClient(String token, String pan, String type) throws Exception {
        MvcResult r = mvc.perform(authed(post("/api/v1/clients"), token).content(body(clientBody(pan, type))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.clientCode").exists())
                .andReturn();
        return read(r).get("id").asString();
    }

    private JsonNode move(String token, String clientId, String target) throws Exception {
        return read(mvc.perform(authed(post("/api/v1/clients/" + clientId + "/status"), token)
                .content(body(Map.of("targetStatus", target)))).andExpect(status().isOk()).andReturn());
    }

    @Test
    void validatesPanFormatEntityTypeAndUniqueness() throws Exception {
        TestUser acc = createUser("ACCOUNTANT");
        mvc.perform(authed(post("/api/v1/clients"), acc.token()).content(body(clientBody("BADPAN", "INDIVIDUAL"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("pan"));
        mvc.perform(authed(post("/api/v1/clients"), acc.token())
                        .content(body(clientBody(randomPan('P'), "PRIVATE_LIMITED"))))
                .andExpect(status().isBadRequest());
        String pan = randomPan('C');
        createClient(acc.token(), pan, "PRIVATE_LIMITED");
        mvc.perform(authed(post("/api/v1/clients"), acc.token()).content(body(clientBody(pan.toLowerCase(),
                "PUBLIC_LIMITED")))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAN_EXISTS"));
    }

    @Test
    void panIsEncryptedAtRestAndMaskedForUsersWithoutPiiPermission() throws Exception {
        TestUser acc = createUser("ACCOUNTANT");
        TestUser viewer = createUser("VIEWER");
        String pan = randomPan('F');
        String id = createClient(acc.token(), pan, "LLP");
        assertThat(getJson("/api/v1/clients/" + id, acc.token()).get("pan").asString()).isEqualTo(pan);
        JsonNode masked = getJson("/api/v1/clients/" + id, viewer.token());
        assertThat(masked.get("pan").asString()).isEqualTo("******" + pan.substring(6));
        assertThat(masked.get("panRevealed").asBoolean()).isFalse();
        // Search by exact PAN works through the blind index.
        JsonNode found = getJson("/api/v1/clients?q=" + pan, viewer.token());
        assertThat(found.get("totalItems").asLong()).isEqualTo(1);
    }

    @Test
    void onboardingRequiresAssignmentsAndMakerChecker() throws Exception {
        TestUser accountant = createUser("ACCOUNTANT");
        TestUser partner = createUser("PARTNER");
        TestUser partner2 = createUser("PARTNER");
        String id = createClient(accountant.token(), randomPan('C'), "PRIVATE_LIMITED");

        move(accountant.token(), id, "DOCUMENTS_PENDING");
        move(accountant.token(), id, "UNDER_VERIFICATION");
        mvc.perform(authed(post("/api/v1/clients/" + id + "/status"), accountant.token())
                        .content(body(Map.of("targetStatus", "ACTIVE"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        mvc.perform(authed(post("/api/v1/clients/" + id + "/status"), accountant.token())
                        .content(body(Map.of("targetStatus", "READY_FOR_APPROVAL"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ASSIGNMENT_REQUIRED"));

        // Assign accountant + reviewer; reviewer must be able to approve and differ from accountant.
        JsonNode client = getJson("/api/v1/clients/" + id, accountant.token());
        Map<String, Object> update = new HashMap<>(clientBody(client.get("pan").asString(), "PRIVATE_LIMITED"));
        update.put("accountantId", accountant.id());
        update.put("reviewerId", accountant.id());
        update.put("version", client.get("version").asLong());
        mvc.perform(authed(put("/api/v1/clients/" + id), accountant.token()).content(body(update)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reviewerId"));
        update.put("reviewerId", partner.id());
        mvc.perform(authed(put("/api/v1/clients/" + id), accountant.token()).content(body(update)))
                .andExpect(status().isOk());

        // Partner submits, so the same partner cannot approve; a second partner can.
        move(partner.token(), id, "READY_FOR_APPROVAL");
        mvc.perform(authed(post("/api/v1/clients/" + id + "/status"), accountant.token())
                .content(body(Map.of("targetStatus", "ACTIVE")))).andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/v1/clients/" + id + "/status"), partner.token())
                        .content(body(Map.of("targetStatus", "ACTIVE"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAKER_CHECKER"));
        assertThat(move(partner2.token(), id, "ACTIVE").get("status").asString()).isEqualTo("ACTIVE");

        // PAN is locked once active.
        JsonNode active = getJson("/api/v1/clients/" + id, accountant.token());
        update.put("pan", randomPan('C'));
        update.put("version", active.get("version").asLong());
        mvc.perform(authed(put("/api/v1/clients/" + id), accountant.token()).content(body(update)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDENTITY_LOCKED"));

        JsonNode history = getJson("/api/v1/clients/" + id + "/status-history", accountant.token());
        assertThat(history.size()).isEqualTo(5);
        assertThat(history.get(0).get("toStatus").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void closingRequiresReasonAndMakesClientReadOnly() throws Exception {
        TestUser partner = createUser("PARTNER");
        String id = createClient(partner.token(), randomPan('P'), "INDIVIDUAL");
        mvc.perform(authed(post("/api/v1/clients/" + id + "/status"), partner.token())
                        .content(body(Map.of("targetStatus", "CLOSED"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("closureReason"));
        mvc.perform(authed(post("/api/v1/clients/" + id + "/status"), partner.token())
                        .content(body(Map.of("targetStatus", "CLOSED", "closureReason", "Engagement ended"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(authed(post("/api/v1/clients/" + id + "/contacts"), partner.token())
                        .content(body(Map.of("name", "Someone", "primary", true))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIENT_CLOSED"));
        // Still readable.
        assertThat(getJson("/api/v1/clients/" + id, partner.token()).get("closureReason").asString())
                .isEqualTo("Engagement ended");
    }

    @Test
    void gstRegistrationMustMatchClientPan() throws Exception {
        TestUser acc = createUser("ACCOUNTANT");
        String pan = randomPan('C');
        String id = createClient(acc.token(), pan, "PRIVATE_LIMITED");
        String first14 = "29" + pan + "1Z";
        String gstin = first14 + checkChar(first14);
        Map<String, Object> reg = new HashMap<>(Map.of("gstin", gstin, "registrationType", "REGULAR",
                "effectiveDate", "2024-04-01", "filingFrequency", "MONTHLY"));
        mvc.perform(authed(post("/api/v1/clients/" + id + "/gst-registrations"), acc.token()).content(body(reg)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stateName").value("Karnataka"));
        mvc.perform(authed(post("/api/v1/clients/" + id + "/gst-registrations"), acc.token()).content(body(reg)))
                .andExpect(status().isConflict());

        String otherPan = randomPan('C');
        String other14 = "27" + otherPan + "1Z";
        reg.put("gstin", other14 + checkChar(other14));
        mvc.perform(authed(post("/api/v1/clients/" + id + "/gst-registrations"), acc.token()).content(body(reg)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("gstin"));
        reg.put("gstin", first14 + (checkChar(first14) == 'A' ? 'B' : 'A'));
        mvc.perform(authed(post("/api/v1/clients/" + id + "/gst-registrations"), acc.token()).content(body(reg)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bankAccountNumbersAreMaskedWithoutPiiPermission() throws Exception {
        TestUser acc = createUser("ACCOUNTANT");
        TestUser viewer = createUser("VIEWER");
        String id = createClient(acc.token(), randomPan('C'), "PRIVATE_LIMITED");
        mvc.perform(authed(post("/api/v1/clients/" + id + "/bank-accounts"), acc.token()).content(body(Map.of(
                        "bankName", "HDFC Bank", "accountHolder", "Acme", "accountNumber", "50100123456789",
                        "ifsc", "HDFC0001234", "accountType", "CURRENT"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.accountNumber").value("50100123456789"));
        JsonNode list = getJson("/api/v1/clients/" + id + "/bank-accounts", viewer.token());
        assertThat(list.get(0).get("accountNumber").asString()).isEqualTo("********6789");
    }

    @Test
    void exportIsAuditedAndNeutralisesFormulas() throws Exception {
        TestUser partner = createUser("PARTNER");
        Map<String, Object> b = new HashMap<>(clientBody(randomPan('C'), "PRIVATE_LIMITED"));
        b.put("displayName", "=HYPERLINK(\"x\")");
        mvc.perform(authed(post("/api/v1/clients"), partner.token()).content(body(b))).andExpect(status().isCreated());
        String csv = mvc.perform(authed(get("/api/v1/clients/export?q=HYPERLINK"), partner.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"");
        JsonNode audit = getJson("/api/v1/audit-logs?action=CLIENTS_EXPORTED", admin());
        assertThat(audit.get("totalItems").asLong()).isGreaterThan(0);
    }

    private static char checkChar(String first14) {
        // Same algorithm as production, recomputed here so the test does not depend on package-private code.
        String cs = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int p = cs.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += p / 36 + p % 36;
        }
        char c = cs.charAt((36 - sum % 36) % 36);
        assertThat(IndianIds.isValidGstin(first14 + c)).isTrue();
        return c;
    }
}
