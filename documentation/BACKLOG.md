# TAXPILOT AI — Backlog

Legend: **P1** Phase 1 carry-over · **P2** Phase 2 (accounting engine) · later phases summarised.

## Phase 1 carry-over
- [ ] MFA (TOTP) enrolment and enforcement for ADMIN/PARTNER
- [ ] Forgot-password flow (needs SMTP provider)
- [ ] Redis-backed distributed rate limiting (current limiter is in-process)
- [ ] Document versions (re-upload as new version of an existing document)
- [ ] Client KYC checklist per entity type
- [ ] Playwright end-to-end suite
- [ ] Encryption-key rotation tooling

## Phase 2 — Accounting engine
- [ ] Accounting periods (open / soft-close / closed) per client and FY
- [ ] Chart of accounts: groups and ledgers, parent-child, codes, standard Indian template seeded per entity type
- [ ] Opening balances with validation (Dr = Cr)
- [ ] Journal entries: draft → review → approve → post; Dr = Cr enforced in engine **and** DB constraint trigger
- [ ] Posted journals immutable; reversal + replacement entries only
- [ ] Idempotency-Key on post/approve endpoints
- [ ] Sales and purchase invoices (with GST lines, `NUMERIC(19,2)`), vendor and customer masters
- [ ] General ledger, ledger extracts, cash book, bank book
- [ ] Trial balance, P&L, balance sheet, cash flow (indirect) from posted ledger data only
- [ ] Bank statement import (CSV/XLSX), normalisation, matching engine (exact / tolerance / reference), exception queue, approval
- [ ] Generic workflow + approvals tables (maker-checker thresholds configurable)
- [ ] Redis + background job runner with retries and dead-letter
- [ ] PDF / Excel export with report metadata (prepared by, reviewed by, version)
- [ ] Dashboard: transactions, reconciliation status, pending approvals

## Phase 3 — GST
Registrations master enhancements, sales/purchase registers, GSTR-1, GSTR-2B import + reconciliation,
ITC ledger and eligibility review, GSTR-3B, challans, versioned GST rule tables.

## Phase 4 — Income tax
Taxpayer profiles, AIS/TIS/26AS/Form 16 import, income heads, deductions, regime comparison, ITR-1..7 drafts.

## Phase 5 — TDS/TCS, AP, AR, fixed assets, inventory, compliance calendar

## Phase 6 — AI workforce (Python FastAPI service, orchestrator, 9 agents, document extraction)

## Phase 7 — GSP / ERI / bank integrations (mock adapters first, clearly labelled)

## Phase 8 — Production readiness
