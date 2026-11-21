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

## 2026-11-21 — Session 1 (continued): Phase 2A accounting engine (branch `feature`)

**Implemented features**
- Chart of accounts per client: groups/ledgers, parent-child, codes, subtypes; standard Indian template
  (`IN-STANDARD` v1, Schedule III headings, GST input/output/RCM ledgers) applied in one step.
- Financial years as twelve monthly periods from the client's FY start month; period close (blocked while journals
  await approval/posting) and reopen with a mandatory, audited reason.
- Journals: draft → submit → approve → post, reject back to draft, cancel. Preparer/submitter can never approve or
  post. Gap-free voucher numbers `JV/<FY>/<00001>`. `Idempotency-Key` on create. Reversals are drafts with
  swapped sides that go through the same approval path and link both entries.
- Database triggers: posted entries and lines immutable; posting requires balanced lines on active ledger accounts
  of the client in an open period. Journals require an ACTIVE client.
- Reports from posted entries only: general ledger (opening, running, closing), trial balance, profit and loss,
  balance sheet (with "profit for the period" and a balance check). CSV export for each.
- Web app: Accounting workspace (guided setup, 7 tabs), journal editor with exact BigInt paise totals and live
  balance indicator, journal view with approval trail; dashboard journal metrics; "Books" button on client profile.

**Migrations** — V6 accounting tables + triggers · V7 permissions `ACCOUNTING_READ/WRITE/APPROVE`
(ADMIN, PARTNER: all; ACCOUNTANT: read+write; VIEWER: read).

**API** — `documentation/api/API-Phase2-Accounting.md`. **ERD** — updated in `documentation/database/ERD.md`.

**Tests**
| Suite | Count | Result |
|---|---|---|
| Backend unit (incl. money, FY parsing, template well-formedness, journal totals) | 27 | pass |
| Backend integration (incl. 10 accounting scenarios: lifecycle, idempotency, maker-checker, invalid lines, DB immutability, hand-computed TB/P&L/BS/GL, reversal, period close/reopen, inactive client, viewer rights) | 30 | pass |
| Frontend unit (incl. paise arithmetic, Indian formatting) | 12 | pass |
| Production build | — | pass |
| Browser end-to-end: setup books → partner prepares capital journal (₹0.01 imbalance shown, then fixed) → submit → admin approves and posts `JV/2026-27/00001` → two more journals → trial balance ₹7,36,000 both sides, P&L profit ₹1,55,000, balance sheet ₹6,91,000 balanced — all matching hand calculation | — | pass |

**Defects found and fixed** — chart template failed to load under Jackson 3 (missing booleans are rejected);
"Draft journal" title shown for pending entries. Dev server lost its config when a branch switch briefly removed the
frontend folder; docs are now edited in a separate git worktree.

**Known limitations** — no bank reconciliation, invoices, cash flow, year-end close or PDF export yet (Phase 2B);
every journal needs approval (no threshold rules yet); one chart template for all entity types.

## 2026-11-21 — Session 1 (continued): Phase 2B part 1 (branch `feature`)

**Decisions recorded (from the product owner)**
- Sample bank statements: synthetic files in five bank layouts (`database/seed-data/bank-statements`, clearly marked).
- Approval stays mandatory; an organisation setting chooses whether the same person may approve (default: no).
- Invoice numbering `INV/26-27/00001`, one series per GSTIN, prefix editable per client (16-character GST limit).
- Letterhead: TAXPILOT AI placeholders, editable in Settings → Organisation.

**Implemented** — organisation settings (letterhead, approval policy); customers/vendors; GST sales and purchase
invoices with versioned rate slabs, intra/inter-state split, RCM, ITC, duplicate supplier-invoice check, approve-and-post
creating a posted journal; bank statement import (CSV/XLS/XLSX, header detection for HDFC/ICICI/SBI/Axis/Kotak/generic,
running-balance check, overlap protection), exact auto-match, probable/partial suggestions with evidence, manual
match/unmatch/exclude, draft journals from bank lines, bank reconciliation statement, maker-checker sign-off, discard.

**Migrations** — V8 organisation settings + `SETTINGS_WRITE` · V9 parties, GST rate slabs, invoice series and
invoices (+ immutability triggers), party on journal lines · V10 bank imports and transactions.

**API** — `documentation/api/API-Phase2B-Invoices-Banking.md`.

**Tests**
| Suite | Count | Result |
|---|---|---|
| Backend unit (adds statement parser over all five sample layouts + XLSX, GST calculator, numbering limit) | 43 | pass |
| Backend integration (adds 7 invoice and 4 reconciliation scenarios, self-approval policy) | 41 | pass |
| Frontend unit (adds exact GST preview) | 13 | pass |
| Browser end-to-end: add customer by GSTIN → sales invoice 50 × ₹840.50 @ 18% (preview = server: ₹49,589.50) → submit → partner approves → `INV/26-27/00001`, journal `JV/2026-27/00004` → import HDFC sample → capital auto-matched (exact 95%) → rent suggested (probable 80%, 2 days apart) and matched → reconciliation statement balances to ₹6,92,132 | — | pass |

**Defects found and fixed** — GST rate (and saved account) selects lost their value when option lists loaded after the
form (would have submitted 0%); statement import defaulted to the cash ledger (bank ledgers now first); bank names shown
as "Hdfc". Added "discard import" so a wrong import can be undone.

**Known limitations** — GST rate slabs await tax-expert review; no credit/debit notes, printable invoice PDF,
ageing, cash flow, year-end close or PDF/Excel reports yet (part 2); unrecognised bank layouts need a mapping screen;
PDF statements are not parsed.

## 2026-11-21 — Session 1 (continued): Phase 2B part 2 (branch `feature`)

**Implemented** — cash flow statement (indirect method) with a cash-flow category per ledger; previous-year comparison
on P&L and balance sheet; year-end close (draft closing journal → normal approval → posting closes the year, reopening
blocked, stale entries refused); recurring journal templates generating idempotent drafts; credit notes (against sales)
and debit notes (against purchases) with their own CN/DN series, capped at the original's remaining value; customer /
vendor on receivable and payable lines (journals and bank-created journals); receivable / payable ageing that
reconciles to the control ledger; sales and purchase registers; PDF and Excel export of every report on the letterhead
(audited); printable tax invoice / credit note / debit note with amount in words and a draft watermark; GST rate slab
review by a tax approver (`TAX_RULES_APPROVE`); Settings → Tax rules.

**Migration** — V11: cash-flow category (back-filled), `CLOSING` journal type, recurring journal tables, credit/debit
note columns and constraints, note series kinds, `TAX_RULES_APPROVE` permission.

**API** — `documentation/api/API-Phase2B2-Reports-Notes-Close.md`.

**Tests**
| Suite | Count | Result |
|---|---|---|
| Backend unit (adds amount in words, Indian grouping, PDF/XLSX rendering, ageing buckets) | 47 | pass |
| Backend integration (adds cash flow + comparisons, year-end close incl. stale/locked year, recurring generation, party lines, all nine exports, GST rate review, credit note + cap + register + ageing, debit note, invoice PDF) | 50 | pass |
| Frontend unit (adds previous-year dates, party-line rules) | 17 | pass |
| Browser: cash flow on demo books reconciles (₹4,55,000); PDF + Excel exports download; credit note raised from INV/26-27/00001 (5 rolls, ₹4,958.96) with locked inherited fields; draft note PDF watermarked; invoice PDF checked against Rule 46 particulars; ageing agrees with control ledger; P&L comparison; recurring dialog; Tax rules tab | — | pass |

**Defects found and fixed** — recurring generation treated every open period as "no financial year" (`Optional.map`
returning null); `invoice_type` column too short for `CREDIT_NOTE`; the rupee sign has no glyph in the standard PDF
fonts (printed as "Rs."); account dialog dropped the locked Type/Group fields from the submitted form, so accounts with
postings could not be edited.

**Demo data note** — a draft credit note against INV/26-27/00001 was left in the demo client for trying the approval
flow. The demo year has not been closed and no GST slab has been approved (both are decisions for a reviewer).

**Known limitations** — ageing allocates balances to the newest invoices (no explicit receipt-to-invoice allocation
yet); entries posted before party lines existed show as "unassigned"; budgets and cash/bank book views not built; no
column-mapping screen for unknown bank layouts; reports carry "prepared by" but no "reviewed by" sign-off yet.

### Next: Phase 3 (GST) — see `documentation/BACKLOG.md`.
