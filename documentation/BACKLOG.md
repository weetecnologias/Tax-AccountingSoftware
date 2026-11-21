# TAXPILOT AI — Backlog

Legend: **P1** Phase 1 carry-over · **P2** Phase 2 (accounting engine) · later phases summarised.

## Phase 1 — delivered 2026-11-21 (see PROGRESS.md)

## Phase 1 carry-over
- [ ] CI pipeline (GitHub Actions: `mvn verify`, `npm test`, `npm run build`) and branch protection on `main`
- [ ] Route-level code splitting (frontend bundle is ~1 MB)
- [ ] Log each reveal of full PAN / bank number (access log), not only downloads
- [ ] Persist `Document` and other non-BaseEntity rows via `persist()` (currently `merge()`, functionally correct)
- [ ] Add Mockito as a Java agent in the test JVM (JDK self-attach warning)
- [ ] MFA (TOTP) enrolment and enforcement for ADMIN/PARTNER
- [ ] Forgot-password flow (needs SMTP provider)
- [ ] Redis-backed distributed rate limiting (current limiter is in-process)
- [ ] Document versions (re-upload as new version of an existing document)
- [ ] Client KYC checklist per entity type
- [ ] Playwright end-to-end suite
- [ ] Encryption-key rotation tooling

## Phase 2A — Accounting engine core (delivered 2026-11-21)
- [x] Accounting periods (monthly, open / closed, reopen with reason) per client and FY
- [x] Chart of accounts: groups and ledgers, parent-child, codes, standard Indian template (one template; per-entity variants later)
- [x] Opening balances via `OPENING` journals (same Dr = Cr rules)
- [x] Journal entries: draft → submit → approve → post; Dr = Cr enforced in engine **and** DB trigger
- [x] Posted journals immutable (DB-enforced); reversal entries through the same approval path
- [x] Idempotency-Key on journal create; repeated post is a no-op
- [x] General ledger, trial balance, P&L, balance sheet from posted entries only; CSV export
- [x] Dashboard: journals awaiting approval / posting, posted this month

## Phase 2B part 1 — delivered 2026-11-21
- [x] Organisation letterhead and approval policy (self-approval allowed or not; audited)
- [x] Customers / vendors; party sub-ledger on journal lines
- [x] Sales and purchase invoices with GST lines, rate master, RCM, ITC, numbering per GSTIN, duplicate detection
- [x] Bank statement import (CSV/XLS/XLSX; HDFC, ICICI, SBI, Axis, Kotak, generic), overlap protection,
      exact auto-matching, suggestions, manual match / exclude, draft journals, reconciliation statement, sign-off, discard

## Phase 2B part 2 — delivered 2026-11-21
- [x] Cash flow statement (indirect method) with per-ledger cash-flow category
- [x] Recurring journals; year-end close (P&L to retained earnings, year locked)
- [x] Comparison with the previous year (P&L, balance sheet)
- [x] PDF / Excel exports of every report on the letterhead (prepared by, generated at; audited)
- [x] Credit / debit notes against posted invoices; sales and purchase registers; receivable / payable ageing
- [x] Printable tax invoice / credit note / debit note (PDF)
- [x] Tax-expert review of the GST rate master (approve per slab, audited)

## Phase 2B — carried forward
- [ ] Cash book and bank book views; budget comparison; monthly / quarterly / half-yearly presets
- [ ] Receipt / payment allocation to specific invoices (ageing currently allocates newest-first)
- [ ] "Reviewed by" sign-off and version on exported reports
- [ ] Bank import column-mapping screen for layouts the header detection cannot recognise; PDF statements
- [ ] Redis + background job runner with retries and dead-letter (large statement imports)
- [ ] Per-entity chart templates (individual, firm, company) and template versioning UI

## Phase 3 — GST
Registrations master enhancements, sales/purchase registers, GSTR-1, GSTR-2B import + reconciliation,
ITC ledger and eligibility review, GSTR-3B, challans, versioned GST rule tables.

## Phase 4 — Income tax
Taxpayer profiles, AIS/TIS/26AS/Form 16 import, income heads, deductions, regime comparison, ITR-1..7 drafts.

## Phase 5 — TDS/TCS, AP, AR, fixed assets, inventory, compliance calendar

## Phase 6 — AI workforce (Python FastAPI service, orchestrator, 9 agents, document extraction)

## Phase 7 — GSP / ERI / bank integrations (mock adapters first, clearly labelled)

## Phase 8 — Production readiness
