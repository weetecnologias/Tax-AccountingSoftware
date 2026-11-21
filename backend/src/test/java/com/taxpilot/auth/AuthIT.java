package com.taxpilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.taxpilot.ApiTestSupport;

import jakarta.servlet.http.Cookie;

class AuthIT extends ApiTestSupport {

    @Autowired
    LoginHistoryRepository loginHistory;

    @Test
    void wrongPasswordIsGenericAndRecorded() throws Exception {
        TestUser u = createUser("VIEWER");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", u.email(), "password", "NotThePassword1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").exists());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", "nobody@test.local", "password", "NotThePassword1"))))
                .andExpect(status().isUnauthorized());
        assertThat(loginHistory.findByEmailOrderByOccurredAtDesc(u.email()))
                .anyMatch(h -> !h.isSuccess() && "BAD_PASSWORD".equals(h.getFailureReason()));
    }

    @Test
    void fiveFailuresLockTheAccountEvenWithTheRightPassword() throws Exception {
        TestUser u = createUser("VIEWER");
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("email", u.email(), "password", "WrongPassword" + i))))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", u.email(), "password", USER_PASSWORD))))
                .andExpect(status().isUnauthorized());
        assertThat(loginHistory.findByEmailOrderByOccurredAtDesc(u.email()).getFirst().getFailureReason())
                .isEqualTo("LOCKED");
    }

    @Test
    void temporaryPasswordBlocksApiUntilChanged() throws Exception {
        String email = "temp" + System.nanoTime() + "@test.local";
        mvc.perform(authed(post("/api/v1/users"), admin()).content(body(Map.of("email", email, "fullName", "Temp",
                "temporaryPassword", "TempPassword123", "roles", list("VIEWER"))))).andExpect(status().isCreated());
        String temp = login(email, "TempPassword123");
        mvc.perform(authed(get("/api/v1/clients"), temp))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(authed(get("/api/v1/auth/me"), temp)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(authed(post("/api/v1/auth/change-password"), temp)
                        .content(body(Map.of("currentPassword", "TempPassword123", "newPassword", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
    }

    @Test
    void refreshRotatesTokenAndDetectsReuse() throws Exception {
        TestUser u = createUser("VIEWER");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", u.email(), "password", USER_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(AuthController.REFRESH_COOKIE, true))
                .andReturn();
        Cookie first = login.getResponse().getCookie(AuthController.REFRESH_COOKIE);

        // Missing CSRF header is refused.
        mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andExpect(status().isForbidden());

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(first)
                .header(AuthController.CSRF_HEADER, "taxpilot")).andExpect(status().isOk()).andReturn();
        Cookie second = refreshed.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        String newAccess = read(refreshed).get("accessToken").asString();
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        mvc.perform(authed(get("/api/v1/auth/me"), newAccess)).andExpect(status().isOk());

        // Replaying the rotated-out token ends every session, including the new one.
        mvc.perform(post("/api/v1/auth/refresh").cookie(first).header(AuthController.CSRF_HEADER, "taxpilot"))
                .andExpect(status().isUnauthorized());
        mvc.perform(authed(get("/api/v1/auth/me"), newAccess)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(second).header(AuthController.CSRF_HEADER, "taxpilot"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheAccessTokenImmediately() throws Exception {
        TestUser u = createUser("VIEWER");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", u.email(), "password", USER_PASSWORD)))).andReturn();
        String token = read(login).get("accessToken").asString();
        Cookie refresh = login.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        mvc.perform(authed(get("/api/v1/clients"), token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").cookie(refresh).header(AuthController.CSRF_HEADER, "taxpilot"))
                .andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/clients"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatingAUserEndsTheirSessions() throws Exception {
        TestUser u = createUser("VIEWER");
        mvc.perform(authed(get("/api/v1/clients"), u.token())).andExpect(status().isOk());
        long version = read(mvc.perform(authed(get("/api/v1/users/" + u.id()), admin())).andReturn())
                .get("version").asLong();
        mvc.perform(authed(put("/api/v1/users/" + u.id()), admin()).content(body(Map.of("fullName", "Gone",
                "active", false, "roles", list("VIEWER"), "version", version)))).andExpect(status().isOk());
        mvc.perform(authed(get("/api/v1/clients"), u.token())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", u.email(), "password", USER_PASSWORD)))).andExpect(status().isUnauthorized());
    }

    @Test
    void permissionsAreEnforcedServerSide() throws Exception {
        TestUser viewer = createUser("VIEWER");
        mvc.perform(authed(get("/api/v1/users"), viewer.token())).andExpect(status().isForbidden());
        mvc.perform(authed(get("/api/v1/audit-logs"), viewer.token())).andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/v1/clients"), viewer.token())
                .content(body(clientBody(randomPan('C'), "PRIVATE_LIMITED")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/clients").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCannotDeactivateThemselves() throws Exception {
        String me = getJson("/api/v1/auth/me", admin()).get("id").asString();
        long version = getJson("/api/v1/users/" + me, admin()).get("version").asLong();
        mvc.perform(authed(put("/api/v1/users/" + me), admin()).content(body(Map.of("fullName", "Admin",
                        "active", false, "roles", list("ADMIN"), "version", version))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_DEACTIVATION"));
    }
}
