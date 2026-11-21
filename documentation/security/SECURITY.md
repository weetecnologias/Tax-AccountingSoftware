# TAXPILOT AI — Security Controls (Phase 1)

| Area | Control |
|---|---|
| Passwords | BCrypt cost 12; min 12 chars with upper, lower, digit; forced change after admin reset |
| Brute force | Per-account lockout (5 failures / 15 min) + per-IP rate limit on login and refresh |
| Sessions | 15-min JWT access token held in memory; 7-day rotating refresh token in `HttpOnly; Secure (prod); SameSite=Strict` cookie, stored as SHA-256 hash; logout revokes |
| CSRF | API is bearer-token based; the only cookie-authenticated call (`/auth/refresh`) uses `SameSite=Strict` and a custom-header check |
| Authorisation | Permission checks on every endpoint (`@PreAuthorize`); UI hiding is convenience only |
| Maker-checker | Client activation and document review refuse self-approval |
| PII | PAN and bank numbers AES-256-GCM encrypted, HMAC blind index for uniqueness, masked unless `CLIENT_PII_READ` |
| Files | Content-type sniffing, 25 MB limit, ClamAV scan, encrypted at rest, random storage keys, original name never used as a path |
| Audit | Append-only table enforced by trigger; no secrets or full identifiers in details |
| Secrets | Only from environment variables; `.env` is git-ignored; dev defaults in `.env.example` are for local use only |
| Transport | TLS terminated at the reverse proxy in staging/prod; HSTS enabled |
| Headers | CSP, X-Content-Type-Options, Referrer-Policy, frame-ancestors none |
| Logs | No request bodies, tokens or identifiers logged; request id on every line |

Not yet implemented (tracked in BACKLOG.md): MFA (TOTP), password-reset email, Redis-backed distributed
rate limit, key rotation tooling, backup/restore drills, penetration test.

Government portal passwords and OTPs are never stored.
