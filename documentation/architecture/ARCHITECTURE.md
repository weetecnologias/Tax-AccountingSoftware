# TAXPILOT AI — Architecture

Status: **Approved foundation for Phase 1** · Last updated: 2026-11-21

## 1. Style

A **modular monolith** (Spring Boot) with hard module boundaries, plus a separate **Python AI service**
introduced in Phase 6. No microservices until load or team structure justifies one.

```
React + TypeScript (Vite)            frontend/
        │  HTTPS, JSON, JWT bearer
        ▼
Spring Boot 4 API  /api/v1           backend/
  ├─ auth        login, refresh, sessions, lockout, rate limit
  ├─ users       users, roles, permissions
  ├─ clients     client master, registrations, contacts, banks, onboarding state machine
  ├─ documents   upload, validation, malware scan, encrypted storage, review
  ├─ audit       append-only audit log
  ├─ dashboard   metrics computed from live tables
  └─ common      errors, paging, crypto, validation (PAN / TAN / GSTIN)
        │
        ├── PostgreSQL 17 (Flyway migrations)
        ├── S3-compatible object storage (MinIO in dev), AES-256-GCM encrypted at app level
        ├── ClamAV (clamd) malware scanning — optional in dev, mandatory in prod
        └── Redis (Phase 2+: job queues, distributed rate limiting)

Phase 6:  Python FastAPI AI service ── called by backend only, via typed tool contracts
```

## 2. Module rules

1. A module exposes a service interface and DTOs; other modules never touch its repositories or entities.
2. Cross-module calls go through services (`ClientService`, `AuditService`, …).
3. Every state change that matters writes an `audit_logs` row **in the same transaction**.
4. Financial and statutory calculations live in deterministic engines (Phase 2+). LLMs never produce final figures.
5. Statutory rules (rates, due dates, thresholds) are versioned, effective-dated data — never constants in code.

## 3. Key decisions

| Decision | Choice | Why |
|---|---|---|
| Backend | Java 21 (bytecode target), Spring Boot 4.1 | Spec; current supported Boot line |
| Auth | Short-lived JWT access token (15 min, HS256) in memory + opaque refresh token (7 days) in an `HttpOnly; SameSite=Strict` cookie, stored hashed server-side | Revocable sessions, no token in `localStorage` |
| Passwords | BCrypt (cost 12), lockout after 5 failures for 15 min | |
| Authorisation | Permission codes (`CLIENT_WRITE`, …) grouped into roles; enforced with `@PreAuthorize` | Fine-grained, auditable |
| Sensitive fields | PAN and bank account numbers encrypted with AES-256-GCM; uniqueness via HMAC-SHA256 blind index; masked in list responses | DPDP-aligned minimisation |
| GSTIN | Stored in clear (it is a public identifier printed on invoices); checksum validated | |
| Documents | Bytes encrypted with AES-256-GCM before reaching storage; SHA-256 of plaintext for duplicate detection | Storage provider never sees plaintext |
| IDs | UUID v4 | Spec; safe to expose |
| Money | `NUMERIC(19,2)` / `BigDecimal` only (Phase 2+) | No floating point |
| Errors | RFC 9457 `application/problem+json` with field errors | Structured, standard |
| API docs | springdoc OpenAPI at `/api/v1/openapi` and Swagger UI at `/swagger-ui` (dev only) | |
| Migrations | Flyway, in `backend/src/main/resources/db/migration` | Spec's `database/migrations` is adapted so migrations ship inside the backend artifact |

## 4. Environments

| Env | Profile | Notes |
|---|---|---|
| Local dev | `dev` | `docker compose up` for Postgres + MinIO; malware scan may be disabled and is then **labelled `NOT_SCANNED`** |
| Test | `test` | Testcontainers PostgreSQL, local filesystem storage |
| Staging / Prod | `prod` | All secrets from environment; ClamAV required; Swagger UI off |

## 5. Request lifecycle

`RequestIdFilter` (assigns `X-Request-Id`) → `RateLimitFilter` (auth endpoints) → Spring Security JWT
resource server → controller (`@Valid` DTOs) → `@PreAuthorize` → service (transactional, audit) → repository.

## 6. Future SaaS readiness

Every client-owned table carries `organisation_id`. Phase 1 runs with exactly one organisation, so no tenant
routing exists yet, but adding row-level isolation later does not need a schema rebuild.
