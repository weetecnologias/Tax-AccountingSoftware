# TAXPILOT AI — Software Requirements, Phase 1

## FR-1 Authentication
- FR-1.1 Users log in with email + password. Wrong credentials return a generic error (no user enumeration).
- FR-1.2 Five consecutive failures lock the account for 15 minutes. Every attempt is written to `login_history`.
- FR-1.3 Login returns a 15-minute access token and sets a 7-day refresh cookie (`HttpOnly`, `SameSite=Strict`, path `/api/v1/auth`).
- FR-1.4 `POST /auth/refresh` rotates the refresh token (old one revoked). `POST /auth/logout` revokes the session.
- FR-1.5 Users created by an administrator must change their temporary password at first login.
- FR-1.6 Login and refresh are rate-limited per IP (default 10/minute).
- FR-1.7 The first administrator is bootstrapped from environment variables only when no user exists.

## FR-2 Users, roles, permissions
- FR-2.1 Administrators list, create, edit, deactivate users and reset their passwords.
- FR-2.2 System roles: `ADMIN`, `PARTNER` (reviewer/approver), `ACCOUNTANT` (preparer), `VIEWER`.
- FR-2.3 Custom roles can be created from the permission catalogue. System roles cannot be deleted.
- FR-2.4 A user cannot deactivate themselves or remove their own `ADMIN` role.

## FR-3 Clients
- FR-3.1 Create a client with entity type, legal name, PAN, optional TAN, financial-year start month, accounting method.
- FR-3.2 PAN format `AAAAA9999A`; the 4th character must match the entity type (P individual, H HUF, F firm/LLP, C company, T trust, A AOP, B BOI, …).
- FR-3.3 TAN format `AAAA99999A`. GSTIN: 15 characters, valid state code, embedded PAN equal to client PAN, valid checksum.
- FR-3.4 PAN is unique within the organisation.
- FR-3.5 Onboarding state machine:
  `DRAFT → DOCUMENTS_PENDING → UNDER_VERIFICATION → READY_FOR_APPROVAL → ACTIVE`,
  `ACTIVE ↔ ON_HOLD`, any non-closed state `→ CLOSED`, and `READY_FOR_APPROVAL → UNDER_VERIFICATION` (sent back).
- FR-3.6 Activation requires permission `CLIENT_APPROVE`, an assigned accountant and reviewer, and the approver
  must not be the user who moved the client to `READY_FOR_APPROVAL` (maker-checker).
- FR-3.7 Closing requires a reason and keeps every record. A closed client is read-only.
- FR-3.8 Sub-records: contacts, addresses, GST registrations (many per client), bank accounts (number encrypted, last 4 shown).
- FR-3.9 Every status change is stored in `client_status_history` with actor, time and comment.

## FR-4 Documents
- FR-4.1 Upload PDF, PNG, JPG, XLSX, XLS, CSV up to 25 MB, linked to a client, a document type and optionally a financial year.
- FR-4.2 The file type is verified from its content (magic bytes), not just the extension.
- FR-4.3 Files are scanned by ClamAV when configured. If scanning is disabled (dev only), `scan_status = NOT_SCANNED` is shown.
  Infected files are rejected and an audit event is written.
- FR-4.4 Bytes are encrypted (AES-256-GCM) before storage. Downloads are permission-checked and audited.
- FR-4.5 A file whose SHA-256 already exists for the same client is stored but marked `DUPLICATE_SUSPECTED`.
- FR-4.6 Review: approve or reject with comment; the uploader cannot review their own document.
- FR-4.7 Uploads to a `CLOSED` client are refused.

## FR-5 Audit
- FR-5.1 Login/logout, user and role changes, client changes, status changes, document upload/download/review are audited.
- FR-5.2 Audit records never contain passwords, tokens, full PAN or bank numbers.
- FR-5.3 Audit records cannot be modified or deleted (database trigger).
- FR-5.4 Users with `AUDIT_READ` can filter the log by actor, action, entity, client and date range.

## FR-6 Dashboard
- FR-6.1 Every number is a live query: total clients, active clients, clients by status, clients awaiting approval,
  documents total, documents awaiting review, suspected duplicates, documents uploaded today, recent activity.
- FR-6.2 Metrics for modules not yet built (GST, ITR, reconciliation) are **not shown**.

## NFR
- API p95 under 1 s for common reads on seed data; dashboard under 3 s.
- All endpoints authenticated except login/refresh/health; structured `problem+json` errors; paginated list endpoints.
- No secrets in the repository; dev defaults live in `.env.example` and are for local use only.
