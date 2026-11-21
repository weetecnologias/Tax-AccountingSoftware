package com.taxpilot.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All application settings. Secrets are supplied through environment variables, never committed. */
@ConfigurationProperties(prefix = "taxpilot")
public record TaxpilotProperties(
        Security security,
        Crypto crypto,
        Storage storage,
        Scan scan,
        Bootstrap bootstrap,
        Cors cors) {

    public record Security(
            String jwtSecret,
            String jwtIssuer,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            boolean cookieSecure,
            int authRequestsPerMinute,
            int maxFailedLogins,
            Duration lockoutDuration) {
    }

    /** Base64-encoded 256-bit keys. */
    public record Crypto(String encryptionKey, String hmacKey) {
    }

    public record Storage(String type, String localPath, S3 s3, long maxUploadBytes) {
        public record S3(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        }
    }

    public record Scan(boolean enabled, String host, int port, Duration timeout) {
    }

    public record Bootstrap(String adminEmail, String adminPassword, String adminName) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
