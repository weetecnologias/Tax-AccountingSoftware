package com.taxpilot.auth;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.common.ApiException;
import com.taxpilot.config.TaxpilotProperties;
import com.taxpilot.security.TokenService;
import com.taxpilot.users.User;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public static final String REFRESH_COOKIE = "tp_refresh";
    public static final String CSRF_HEADER = "X-Requested-With";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final TaxpilotProperties props;

    public AuthController(AuthService auth, TaxpilotProperties props) {
        this.auth = auth;
        this.props = props;
    }

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record MeResponse(UUID id, String email, String fullName, boolean mustChangePassword, Set<String> roles,
                             Set<String> permissions) {
        static MeResponse of(User u) {
            return new MeResponse(u.getId(), u.getEmail(), u.getFullName(), u.isMustChangePassword(), u.roleCodes(),
                    u.permissionCodes());
        }
    }

    public record TokenResponse(String accessToken, long expiresIn, MeResponse user) {
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthResult result = auth.login(request.email(), request.password());
        if (!result.ok()) {
            throw AuthService.unauthorized(result.failure());
        }
        return withSession(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                                 @RequestHeader(name = CSRF_HEADER, required = false) String csrf) {
        requireCsrfHeader(csrf);
        AuthService.AuthResult result = auth.refresh(token);
        if (!result.ok()) {
            return ResponseEntity.status(401).header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
        }
        return withSession(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                       @RequestHeader(name = CSRF_HEADER, required = false) String csrf) {
        requireCsrfHeader(csrf);
        auth.logout(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        return MeResponse.of(auth.me());
    }

    @PostMapping("/change-password")
    public TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        TokenService.IssuedToken token = auth.changePassword(request.currentPassword(), request.newPassword());
        return new TokenResponse(token.value(), token.expiresInSeconds(), MeResponse.of(auth.me()));
    }

    private ResponseEntity<TokenResponse> withSession(AuthService.AuthResult result) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, result.refreshToken())
                .httpOnly(true)
                .secure(props.security().cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(props.security().refreshTokenTtl())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new TokenResponse(result.accessToken().value(), result.accessToken().expiresInSeconds(),
                        MeResponse.of(result.user())));
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(props.security().cookieSecure())
                .sameSite("Strict").path(COOKIE_PATH).maxAge(Duration.ZERO).build();
    }

    private static void requireCsrfHeader(String value) {
        if (!"taxpilot".equals(value)) {
            throw ApiException.forbidden("CSRF_HEADER_MISSING", "Missing X-Requested-With: taxpilot header");
        }
    }
}
