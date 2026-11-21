package com.taxpilot.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.audit.AuditService;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.RequestContext;
import com.taxpilot.common.crypto.BlindIndex;
import com.taxpilot.config.TaxpilotProperties;
import com.taxpilot.security.CurrentUser;
import com.taxpilot.security.TokenService;
import com.taxpilot.users.PasswordPolicy;
import com.taxpilot.users.User;
import com.taxpilot.users.UserRepository;

/**
 * Login, refresh-token rotation, logout and password change.
 * Every method commits its own transaction so failed-login counters and history persist even when the
 * request ultimately fails (the exception is thrown only after the commit, by {@link AuthController}).
 */
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password, or the account is temporarily locked";

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final LoginHistoryRepository loginHistory;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AuditService audit;
    private final TaxpilotProperties props;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(UserRepository users, UserSessionRepository sessions, LoginHistoryRepository loginHistory,
                       PasswordEncoder passwordEncoder, TokenService tokens, AuditService audit,
                       TaxpilotProperties props) {
        this.users = users;
        this.sessions = sessions;
        this.loginHistory = loginHistory;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.audit = audit;
        this.props = props;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-password");
    }

    /** Result of a login or refresh: either tokens, or a failure to report after commit. */
    public record AuthResult(User user, TokenService.IssuedToken accessToken, String refreshToken, String failure) {
        static AuthResult failed(String reason) {
            return new AuthResult(null, null, null, reason);
        }

        public boolean ok() {
            return failure == null;
        }
    }

    @Transactional
    public AuthResult login(String rawEmail, String password) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        Instant now = Instant.now();
        Optional<User> found = users.findByEmailIgnoreCase(email);
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            recordAttempt(null, email, false, "UNKNOWN_USER");
            return AuthResult.failed(INVALID_CREDENTIALS);
        }
        User user = found.get();
        if (!user.isActive()) {
            passwordEncoder.matches(password, dummyHash);
            recordAttempt(user.getId(), email, false, "INACTIVE");
            return AuthResult.failed(INVALID_CREDENTIALS);
        }
        if (user.isLocked(now)) {
            passwordEncoder.matches(password, dummyHash);
            recordAttempt(user.getId(), email, false, "LOCKED");
            return AuthResult.failed(INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.registerFailedLogin(props.security().maxFailedLogins(), now.plus(props.security().lockoutDuration()));
            recordAttempt(user.getId(), email, false, "BAD_PASSWORD");
            if (user.isLocked(now)) {
                audit.recordAs(user.getId(), user.getEmail(), "ACCOUNT_LOCKED", "USER", user.getId(), null,
                        Map.of("lockedUntil", user.getLockedUntil().toString()));
            }
            return AuthResult.failed(INVALID_CREDENTIALS);
        }
        user.registerSuccessfulLogin(now);
        recordAttempt(user.getId(), email, true, null);
        AuthResult result = openSession(user);
        audit.recordAs(user.getId(), user.getEmail(), "LOGIN", "USER", user.getId(), null, null);
        return result;
    }

    @Transactional
    public AuthResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return AuthResult.failed("No session");
        }
        Optional<UserSession> found = sessions.findByRefreshTokenHash(BlindIndex.sha256Hex(refreshToken));
        if (found.isEmpty()) {
            return AuthResult.failed("Session not found");
        }
        UserSession session = found.get();
        Instant now = Instant.now();
        if (session.getRevokedAt() != null) {
            // A rotated-out token was replayed: treat as theft and end every session for the user.
            sessions.revokeAllForUser(session.getUserId(), now);
            audit.recordAs(session.getUserId(),
                    users.findById(session.getUserId()).map(User::getEmail).orElse(null), "REFRESH_TOKEN_REUSE", "USER", session.getUserId(), null, null);
            return AuthResult.failed("Session ended");
        }
        if (!session.isUsable(now)) {
            return AuthResult.failed("Session expired");
        }
        User user = users.findById(session.getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            session.revoke();
            return AuthResult.failed("Session ended");
        }
        session.revoke();
        return openSession(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        sessions.findByRefreshTokenHash(BlindIndex.sha256Hex(refreshToken)).ifPresent(s -> {
            s.revoke();
            String email = users.findById(s.getUserId()).map(User::getEmail).orElse(null);
            audit.recordAs(s.getUserId(), email, "LOGOUT", "USER", s.getUserId(), null, null);
        });
    }

    @Transactional
    public TokenService.IssuedToken changePassword(String currentPassword, String newPassword) {
        CurrentUser me = CurrentUser.require();
        User user = users.findById(me.id()).orElseThrow(() -> ApiException.notFound("User"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.field("currentPassword", "Current password is incorrect");
        }
        PasswordPolicy.check("newPassword", newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.field("newPassword", "The new password must differ from the current one");
        }
        user.changePassword(passwordEncoder.encode(newPassword), false);
        if (me.sessionId() != null) {
            sessions.revokeOthersForUser(user.getId(), me.sessionId(), Instant.now());
        }
        audit.record("PASSWORD_CHANGED", "USER", user.getId(), null, null);
        return tokens.issueAccessToken(user, me.sessionId());
    }

    @Transactional(readOnly = true)
    public User me() {
        return users.findById(CurrentUser.require().id()).orElseThrow(() -> ApiException.notFound("User"));
    }

    private AuthResult openSession(User user) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UserSession session = new UserSession(user.getId(), BlindIndex.sha256Hex(refreshToken),
                Instant.now().plus(props.security().refreshTokenTtl()), RequestContext.clientIp(),
                RequestContext.userAgent());
        sessions.save(session);
        return new AuthResult(user, tokens.issueAccessToken(user, session.getId()), refreshToken, null);
    }

    private void recordAttempt(UUID userId, String email, boolean success, String reason) {
        loginHistory.save(new LoginHistory(userId, email, success, reason, RequestContext.clientIp(),
                RequestContext.userAgent()));
    }

    static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", message);
    }
}
