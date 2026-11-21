# TAXPILOT AI — Progress Log

## 2026-11-21 — Session 1: Phase 0 + Phase 1

### Phase 0 — done
Architecture, ERD, PRD, Phase 1 SRS, API contracts, UI/UX spec, security controls, testing strategy and backlog
(`documentation/`). Branching model set up: `develop` (integration), `feature`, `bugfix`, `docs`; `main` is
production only and has not been pushed to.

### Phase 1 — working foundation (branch `feature`)

**Implemented features**
- Authentication: email/password, BCrypt(12), 5-failure lockout (15 min), per-IP rate limit, 15-min JWT access
  token held in memory, rotating 7-day refresh token in an HttpOnly SameSite=Strict cookie, refresh-token reuse
  detection, immediate session revocation on logout / deactivation / password reset, forced password change
  for temporary passwords, bootstrap admin from environment.
- Users, roles (ADMIN, PARTNER, ACCOUNTANT, VIEWER + custom) and 14 permissions enforced server-side.
- Clients: create/edit, PAN (format + entity-type check, AES-GCM encrypted, HMAC blind index for uniqueness and
  exact search, masked without `CLIENT_PII_READ`), TAN, onboarding state machine with maker-checker activation,
  closure with mandatory reason (records retained, client read-only), status history, contacts, addresses,
  GST registrations (checksum, state code, PAN match, unique), encrypted bank accounts, audited CSV export with
  formula-injection protection.
- Documents: upload (PDF/PNG/JPG/XLSX/XLS/CSV ≤ 25 MB) with magic-byte type checks, ClamAV hook (fails closed;
  labelled `NOT_SCANNED` when disabled in dev), AES-GCM encryption before storage (local disk or S3-compatible),
  SHA-256 duplicate detection, approve/reject/archive with maker-checker, integrity-checked audited downloads.
- Append-only audit log (DB trigger blocks UPDATE/DELETE/TRUNCATE) with filtered viewer.
- Dashboard computed live from the database; metrics for unbuilt modules are not shown.
- Web app: all screens above connected to the API; permission-aware navigation shows implemented modules only.

**Database migrations** (`backend/src/main/resources/db/migration`)
V1 identity · V2 clients · V3 documents · V4 audit (+ immutability trigger) · V5 reference data (organisation,
permissions, system roles). Flyway applies them on startup; Hibernate validates the schema.

**API endpoints** — see `documentation/api/API-Phase1.md`; live OpenAPI at `/api/v1/openapi`.

**UI screens** — Login, Change password, Dashboard, Clients list, New/Edit client, Client profile (6 tabs),
Documents, Audit log, Settings (Users, Roles & permissions).

**Tests executed and results**
| Suite | Count | Result |
|---|---|---|
| Backend unit (validators, crypto, state machine, file sniffing, rate limit) | 22 | pass |
| Backend integration, Testcontainers PostgreSQL (auth, permissions, clients, maker-checker, documents, audit immutability, dashboard) | 20 | pass |
| Frontend unit (Zod validators, API client refresh/error mapping) | 8 | pass |
| Production build (`tsc -b` + `vite build`) | — | pass |
| Manual end-to-end in browser: bootstrap admin forced password change → create client (PAN/entity mismatch rejected) → assign accountant/reviewer → add GSTIN (bad check digit rejected) → verification → submit → self-approval blocked → upload invoice → sign in as partner (forced password change) → approve document → activate client → audit log and dashboard reflect every step; mobile layout checked | — | pass |

**Defects found and fixed during testing**
- New entities with app-assigned UUIDs were merged instead of persisted, so create responses lacked timestamps
  (fixed with `Persistable` on `BaseEntity`; regression assertion added).
- Logout audit rows had no actor email (fixed).
- Tab bar showed a stray vertical scrollbar (fixed).
- npm `.cmd` shims break because the project path contains `&`; npm scripts now call Node entry points directly.

**Known limitations**
- Malware scanning is disabled in local dev (shown as “Not scanned”); ClamAV must be enabled for staging/prod.
- No MFA or self-service password reset yet (needs SMTP); admins reset passwords.
- Rate limiting is in-process (single node). Redis-backed limiter planned.
- Permission changes take effect at the user's next token refresh (≤ 15 minutes).
- Document extraction/OCR is not implemented (Phase 6). No GST/ITR/accounting features yet.
- Frontend bundle is a single ~1 MB chunk; route-level code splitting planned.
- No CI pipeline or deployment yet.

**Security considerations**
- Secrets only from environment / git-ignored `.env`; test keys exist only in `src/test/resources`.
- Losing `TAXPILOT_ENCRYPTION_KEY` makes PANs, bank numbers and stored files unrecoverable — back it up securely.
- Full bank numbers are visible to `CLIENT_PII_READ` holders; per-view access logging is a backlog item.

**Run locally** — see README.md.

### Next: Phase 2 (accounting engine) — see `documentation/BACKLOG.md`.
