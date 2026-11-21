package com.taxpilot.security;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.taxpilot.config.TaxpilotProperties;
import com.taxpilot.users.User;

/** Issues short-lived access tokens. Refresh tokens are opaque random values handled by AuthService. */
@Service
public class TokenService {

    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_PERMISSIONS = "perms";
    public static final String CLAIM_SESSION = "sid";
    public static final String CLAIM_PASSWORD_CHANGE = "pwd_change";

    private final JwtEncoder encoder;
    private final TaxpilotProperties props;

    public TokenService(JwtEncoder encoder, TaxpilotProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public IssuedToken issueAccessToken(User user, UUID sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.security().accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.security().jwtIssuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_NAME, user.getFullName())
                .claim(CLAIM_SESSION, sessionId.toString())
                .claim(CLAIM_PASSWORD_CHANGE, user.isMustChangePassword())
                .claim(CLAIM_PERMISSIONS, user.permissionCodes().stream().toList())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, props.security().accessTokenTtl().toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
