package com.taxpilot.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.taxpilot.common.ApiException;

/** The authenticated staff member, read from the validated JWT. */
public record CurrentUser(UUID id, String email, UUID sessionId, Set<String> permissions) {

    public static CurrentUser orNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            Set<String> perms = jwt.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toUnmodifiableSet());
            String sid = jwt.getToken().getClaimAsString(TokenService.CLAIM_SESSION);
            return new CurrentUser(UUID.fromString(jwt.getToken().getSubject()),
                    jwt.getToken().getClaimAsString(TokenService.CLAIM_EMAIL),
                    sid == null ? null : UUID.fromString(sid), perms);
        }
        return null;
    }

    public static CurrentUser require() {
        CurrentUser user = orNull();
        if (user == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Authentication required");
        }
        return user;
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }
}
