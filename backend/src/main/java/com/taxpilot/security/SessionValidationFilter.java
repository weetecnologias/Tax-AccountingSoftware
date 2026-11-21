package com.taxpilot.security;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.taxpilot.auth.UserSessionRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects access tokens whose session was revoked (logout, password reset, deactivation) or whose user is
 * inactive, so revocation takes effect immediately rather than when the 15-minute token expires.
 */
public class SessionValidationFilter extends OncePerRequestFilter {

    private final UserSessionRepository sessions;

    public SessionValidationFilter(UserSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String sid = jwt.getToken().getClaimAsString(TokenService.CLAIM_SESSION);
            boolean valid = sid != null && sessions.isSessionActive(UUID.fromString(sid),
                    UUID.fromString(jwt.getToken().getSubject()), Instant.now());
            if (!valid) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"code\":\"SESSION_ENDED\","
                        + "\"detail\":\"Your session has ended. Sign in again.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
