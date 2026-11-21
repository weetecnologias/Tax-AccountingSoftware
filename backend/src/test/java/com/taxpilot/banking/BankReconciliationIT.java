package com.taxpilot.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.taxpilot.AccountingTestSupport;

import tools.jackson.databind.JsonNode;

class BankReconciliationIT extends AccountingTestSupport {

    private ResultActions upload(String token, String filename, byte[] data) throws Exception {
        return mvc.perform(multipart("/api/v1/bank-reconciliation/statements")
                .file(new MockMultipartFile("file", filename, "text/csv", data))
                .param("clientId", clientId).param("ledgerAccountId", acct.get("1121"))
                .header("Authorization", "Bearer " + token));
    }

    private JsonNode txnByAmount(String importId, String amount) throws Exception {
        for (JsonNode t : getJson("/api/v1/bank-reconciliation/statements/" + importId + "/transactions", accountant.token())) {
            if (t.get("deposit").asString().equals(amount) || t.get("withdrawal").asString().equals(amount)) return t;
        }
        throw new AssertionError("no transaction for " + amount);
    }

    private byte[] hdfc() throws Exception {
        return Files.readAllBytes(BankStatementParserTest.SAMPLES.resolve("hdfc-sample.csv"));
    }

    @Test
    void fullReconciliationFromImportToSignOff() throws Exception {
        postJournal("2026-04-01", "Capital introduced by promoters", "UTR N091260401123456",
                new Object[]{"1121", "500000", null}, new Object[]{"3100", null, "500000"});
        postJournal("2026-05-01", "Office rent for April 2026", "NEFT 0501",
                new Object[]{"5510", "45000", null}, new Object[]{"1121", null, "45000"});

        JsonNode imported = read(upload(accountant.token(), "hdfc-sample.csv", hdfc())
                .andExpect(status().isCreated()).andReturn());
        String importId = imported.get("statement").get("id").asString();
        assertThat(imported.get("statement").get("bankFormat").asString()).isEqualTo("HDFC");
        assertThat(imported.get("statement").get("lineCount").asInt()).isEqualTo(5);
        assertThat(imported.get("statement").get("closingBalance").asString()).isEqualTo("692132.00");
        // Only the capital receipt is an exact match (same day, same amount, shared UTR).
        assertThat(imported.get("autoMatched").asInt()).isEqualTo(1);
        JsonNode capital = txnByAmount(importId, "500000.00");
        assertThat(capital.get("status").asString()).isEqualTo("MATCHED");
        assertThat(capital.get("matchConfidence").asInt()).isEqualTo(100);

        // The same file cannot be imported twice.
        upload(accountant.token(), "hdfc-sample.csv", hdfc())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_STATEMENT"));

        // Rent was paid two days after it was booked: offered as a probable match, applied by a person.
        JsonNode rent = txnByAmount(importId, "45000.00");
        assertThat(rent.get("status").asString()).isEqualTo("UNMATCHED");
        JsonNode suggestions = getJson("/api/v1/bank-reconciliation/transactions/" + rent.get("id").asString()
                + "/suggestions", accountant.token());
        assertThat(suggestions.get(0).get("matchType").asString()).isEqualTo("PROBABLE");
        assertThat(suggestions.get(0).get("confidence").asInt()).isEqualTo(80);
        mvc.perform(authed(post("/api/v1/bank-reconciliation/transactions/" + rent.get("id").asString() + "/match"),
                accountant.token()).content(body(Map.of("lineId", suggestions.get(0).get("lineId").asString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MATCHED"));

        // Bank charges and the customer receipt become draft journals that go through approval, then auto-match.
        JsonNode charges = txnByAmount(importId, "118.00");
        assertThat(charges.get("categoryHint").asString()).isEqualTo("BANK_CHARGES");
        String chargesJournal = read(mvc.perform(authed(post("/api/v1/bank-reconciliation/transactions/"
                + charges.get("id").asString() + "/create-journal"), accountant.token())
                .content(body(Map.of("counterAccountId", acct.get("5320"))))).andExpect(status().isOk()).andReturn())
                .get("journalId").asString();
        JsonNode draft = getJson("/api/v1/accounting/journals/" + chargesJournal, accountant.token());
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        approveJournal(chargesJournal);
        JsonNode receipt = txnByAmount(importId, "236000.00");
        String receiptJournal = read(mvc.perform(authed(post("/api/v1/bank-reconciliation/transactions/"
                        + receipt.get("id").asString() + "/create-journal"), accountant.token())
                        .content(body(Map.of("counterAccountId", acct.get("1130"), "narration", "Receipt from Mehta Garments"))))
                .andExpect(status().isOk()).andReturn()).get("journalId").asString();
        approveJournal(receiptJournal);
        JsonNode rerun = read(mvc.perform(authed(post("/api/v1/bank-reconciliation/statements/" + importId + "/auto-match"),
                accountant.token())).andExpect(status().isOk()).andReturn());
        assertThat(rerun.get("matched").asInt()).isEqualTo(2);

        // Sign-off is blocked while a line is unmatched.
        mvc.perform(authed(post("/api/v1/bank-reconciliation/statements/" + importId + "/approve"), partner.token()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNMATCHED_LINES"));
        JsonNode interest = txnByAmount(importId, "1250.00");
        mvc.perform(authed(post("/api/v1/bank-reconciliation/transactions/" + interest.get("id").asString() + "/exclude"),
                accountant.token()).content(body(Map.of("reason", "Interest to be booked at quarter end"))))
                .andExpect(status().isOk());

        JsonNode summary = getJson("/api/v1/bank-reconciliation/statements/" + importId + "/summary", accountant.token());
        assertThat(summary.get("balancePerBooks").asString()).isEqualTo("690882.00");
        assertThat(summary.get("bankOnlyDeposits").asString()).isEqualTo("1250.00");
        assertThat(summary.get("balancePerBank").asString()).isEqualTo("692132.00");
        assertThat(summary.get("difference").asString()).isEqualTo("0.00");
        assertThat(summary.get("reconciled").asBoolean()).isTrue();

        mvc.perform(authed(post("/api/v1/bank-reconciliation/statements/" + importId + "/approve"), accountant.token()))
                .andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/v1/bank-reconciliation/statements/" + importId + "/approve"), partner.token())
                .content(body(Map.of("reason", "Reviewed with bank statement")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECONCILED"));
        // Signed-off reconciliations are frozen.
        mvc.perform(authed(post("/api/v1/bank-reconciliation/transactions/" + rent.get("id").asString() + "/unmatch"),
                accountant.token())).andExpect(status().isConflict());
    }

    @Test
    void overlappingStatementsImportOnlyNewTransactions() throws Exception {
        upload(accountant.token(), "hdfc-sample.csv", hdfc()).andExpect(status().isCreated());
        String extended = new String(hdfc(), StandardCharsets.UTF_8).replace(
                "31/05/26,CREDIT INTEREST CAPITALISED,0000000000000000,31/05/26,,1250.00,692132.00",
                "31/05/26,CREDIT INTEREST CAPITALISED,0000000000000000,31/05/26,,1250.00,692132.00\n"
                        + "02/06/26,ATM WDL MG ROAD,0000000000000000,02/06/26,2000.00,,690132.00");
        JsonNode second = read(upload(accountant.token(), "hdfc-june.csv", extended.getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated()).andReturn());
        assertThat(second.get("statement").get("lineCount").asInt()).isEqualTo(1);
        assertThat(second.get("statement").get("duplicateCount").asInt()).isEqualTo(5);
    }

    @Test
    void unsignedImportsCanBeDiscardedAndReimported() throws Exception {
        String importId = read(upload(accountant.token(), "hdfc-sample.csv", hdfc()).andExpect(status().isCreated())
                .andReturn()).get("statement").get("id").asString();
        mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v1/bank-reconciliation/statements/" + importId), accountant.token())).andExpect(status().isNoContent());
        upload(accountant.token(), "hdfc-sample.csv", hdfc()).andExpect(status().isCreated());
        JsonNode audit = getJson("/api/v1/audit-logs?action=BANK_STATEMENT_DISCARDED&entityId=" + importId, admin());
        assertThat(audit.get("totalItems").asLong()).isEqualTo(1);
    }

    @Test
    void importerCannotSignOffTheirOwnReconciliation() throws Exception {
        JsonNode imported = read(upload(partner.token(), "hdfc-sample.csv", hdfc()).andExpect(status().isCreated()).andReturn());
        String importId = imported.get("statement").get("id").asString();
        for (JsonNode t : getJson("/api/v1/bank-reconciliation/statements/" + importId + "/transactions", partner.token())) {
            mvc.perform(authed(post("/api/v1/bank-reconciliation/transactions/" + t.get("id").asString() + "/exclude"),
                    partner.token()).content(body(Map.of("reason", "test")))).andExpect(status().isOk());
        }
        mvc.perform(authed(post("/api/v1/bank-reconciliation/statements/" + importId + "/approve"), partner.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAKER_CHECKER"));
    }
}
