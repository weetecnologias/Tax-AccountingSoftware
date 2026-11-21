package com.taxpilot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared helpers for API integration tests running against a real PostgreSQL container. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestSupport {

    protected static final String ADMIN_EMAIL = "admin@test.local";
    protected static final String ADMIN_BOOTSTRAP_PASSWORD = "AdminTestPass123";
    protected static final String ADMIN_PASSWORD = "AdminChanged456";
    protected static final String USER_PASSWORD = "UserPassword789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    private static String adminToken;

    /** Logs in as the bootstrap admin, changing the temporary password the first time. */
    protected String admin() throws Exception {
        if (adminToken == null) {
            MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("email", ADMIN_EMAIL, "password", ADMIN_BOOTSTRAP_PASSWORD)))).andReturn();
            if (r.getResponse().getStatus() == 200) {
                String temp = read(r).get("accessToken").asString();
                MvcResult changed = mvc.perform(post("/api/v1/auth/change-password").header("Authorization", "Bearer " + temp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("currentPassword", ADMIN_BOOTSTRAP_PASSWORD, "newPassword", ADMIN_PASSWORD))))
                        .andExpect(status().isOk()).andReturn();
                adminToken = read(changed).get("accessToken").asString();
            } else {
                adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
            }
        }
        return adminToken;
    }

    protected String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", email, "password", password)))).andExpect(status().isOk()).andReturn();
        return read(r).get("accessToken").asString();
    }

    /** Creates an active user with the given roles and returns a token after the forced password change. */
    protected TestUser createUser(String... roles) throws Exception {
        String email = "user" + Math.abs(RANDOM.nextLong()) + "@test.local";
        MvcResult created = mvc.perform(authed(post("/api/v1/users"), admin())
                        .content(body(Map.of("email", email, "fullName", "Test " + String.join("/", roles),
                                "temporaryPassword", "TempPassword123", "roles", Set.of(roles)))))
                .andExpect(status().isCreated()).andReturn();
        String id = read(created).get("id").asString();
        String temp = login(email, "TempPassword123");
        MvcResult changed = mvc.perform(authed(post("/api/v1/auth/change-password"), temp)
                        .content(body(Map.of("currentPassword", "TempPassword123", "newPassword", USER_PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return new TestUser(id, email, read(changed).get("accessToken").asString());
    }

    protected record TestUser(String id, String email, String token) {
    }

    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder req, String token) {
        return req.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    protected JsonNode getJson(String url, String token) throws Exception {
        return read(mvc.perform(authed(get(url), token)).andExpect(status().isOk()).andReturn());
    }

    protected String body(Object value) {
        return json.writeValueAsString(value);
    }

    protected JsonNode read(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** A random, structurally valid PAN whose 4th character is the given holder type. */
    protected static String randomPan(char holderType) {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) sb.append(letters.charAt(RANDOM.nextInt(26)));
        sb.append(holderType);
        sb.append(letters.charAt(RANDOM.nextInt(26)));
        for (int i = 0; i < 4; i++) sb.append(RANDOM.nextInt(10));
        sb.append(letters.charAt(RANDOM.nextInt(26)));
        return sb.toString();
    }

    protected Map<String, Object> clientBody(String pan, String entityType) {
        return Map.of("displayName", "Acme " + pan, "legalName", "Acme Traders " + pan, "entityType", entityType,
                "pan", pan, "fyStartMonth", 4, "accountingMethod", "ACCRUAL");
    }

    protected static List<String> list(String... values) {
        return List.of(values);
    }
}
