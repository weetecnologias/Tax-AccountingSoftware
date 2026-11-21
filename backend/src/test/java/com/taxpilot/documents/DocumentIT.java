package com.taxpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.taxpilot.ApiTestSupport;

import tools.jackson.databind.JsonNode;

class DocumentIT extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Value("${taxpilot.storage.local-path}")
    String storagePath;

    private String newClient(String token) throws Exception {
        MvcResult r = mvc.perform(authed(post("/api/v1/clients"), token)
                .content(body(clientBody(randomPan('C'), "PRIVATE_LIMITED")))).andExpect(status().isCreated()).andReturn();
        return read(r).get("id").asString();
    }

    private ResultActions upload(String token, String clientId, String filename, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/documents/upload")
                .file(new MockMultipartFile("file", filename, "application/octet-stream", content))
                .param("clientId", clientId).param("documentType", "PURCHASE_INVOICE").param("financialYear", "2025-26")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    void uploadStoresEncryptedFileAndFlagsDuplicates() throws Exception {
        TestUser acc = createUser("ACCOUNTANT");
        String clientId = newClient(acc.token());
        byte[] pdf = ("%PDF-1.4 invoice " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);

        JsonNode doc = read(upload(acc.token(), clientId, "invoice.pdf", pdf)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.scanStatus").value("NOT_SCANNED"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andReturn());

        // Bytes at rest are not the plaintext.
        String key = jdbc.queryForObject("select storage_key from documents where id = ?::uuid", String.class,
                doc.get("id").asString());
        byte[] stored = Files.readAllBytes(Path.of(storagePath).resolve(key));
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("%PDF");

        upload(acc.token(), clientId, "invoice-copy.pdf", pdf)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DUPLICATE_SUSPECTED"))
                .andExpect(jsonPath("$.duplicateOfId").value(doc.get("id").asString()));
    }

    @Test
    void rejectsMismatchedTypesAndClosedClients() throws Exception {
        TestUser partner = createUser("PARTNER");
        String clientId = newClient(partner.token());
        upload(partner.token(), clientId, "invoice.pdf", "MZ not a pdf".getBytes())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("file"));
        upload(partner.token(), clientId, "tool.exe", "MZ".getBytes()).andExpect(status().isBadRequest());

        mvc.perform(authed(post("/api/v1/clients/" + clientId + "/status"), partner.token())
                .content(body(Map.of("targetStatus", "CLOSED", "closureReason", "Done")))).andExpect(status().isOk());
        upload(partner.token(), clientId, "late.pdf", "%PDF-late".getBytes())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIENT_CLOSED"));
    }

    @Test
    void reviewEnforcesMakerCheckerAndDownloadIsAudited() throws Exception {
        TestUser uploader = createUser("ACCOUNTANT");
        TestUser reviewer = createUser("ACCOUNTANT");
        String clientId = newClient(uploader.token());
        byte[] csv = ("date,amount\n2025-04-01," + System.nanoTime() + "\n").getBytes(StandardCharsets.UTF_8);
        String docId = read(upload(uploader.token(), clientId, "bank.csv", csv).andExpect(status().isCreated())
                .andReturn()).get("id").asString();

        mvc.perform(authed(post("/api/v1/documents/" + docId + "/approve"), uploader.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAKER_CHECKER"));
        mvc.perform(authed(post("/api/v1/documents/" + docId + "/reject"), reviewer.token()).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("comment"));
        mvc.perform(authed(post("/api/v1/documents/" + docId + "/approve"), reviewer.token())
                        .content(body(Map.of("comment", "Matches ledger"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(authed(post("/api/v1/documents/" + docId + "/reject"), reviewer.token())
                        .content(body(Map.of("comment", "again"))))
                .andExpect(status().isConflict());

        byte[] downloaded = mvc.perform(get("/api/v1/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + reviewer.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(csv);

        JsonNode audit = getJson("/api/v1/audit-logs?entityId=" + docId, admin());
        assertThat(audit.get("items").valueStream().map(n -> n.get("action").asString()))
                .contains("DOCUMENT_UPLOADED", "DOCUMENT_APPROVED", "DOCUMENT_DOWNLOADED");
    }

    @Test
    void auditLogCannotBeModifiedOrDeleted() {
        assertThatThrownBy(() -> jdbc.update("update audit_logs set action = 'TAMPERED'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_logs"))
                .hasMessageContaining("append-only");
    }

    @Test
    void dashboardReflectsLiveData() throws Exception {
        TestUser acc = createUser("ACCOUNTANT");
        JsonNode before = getJson("/api/v1/dashboard/summary", acc.token());
        String clientId = newClient(acc.token());
        upload(acc.token(), clientId, "x.pdf", ("%PDF-" + UUID.randomUUID()).getBytes()).andExpect(status().isCreated());
        JsonNode after = getJson("/api/v1/dashboard/summary", acc.token());
        assertThat(after.get("totalClients").asLong()).isEqualTo(before.get("totalClients").asLong() + 1);
        assertThat(after.get("documentsUploadedToday").asLong())
                .isEqualTo(before.get("documentsUploadedToday").asLong() + 1);
        assertThat(after.get("uploadsLast14Days").size()).isEqualTo(14);
        // Accountants lack AUDIT_READ, so no activity feed is exposed to them.
        assertThat(after.get("recentActivity").size()).isZero();
    }
}
