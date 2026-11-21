# TAXPILOT AI — Product Requirements (summary)

The authoritative source is the master specification V3. This file records scope decisions and the
phase plan the team is executing against.

## Product

An AI-assisted accounting, GST, income-tax and compliance department for one internal accounting
organisation serving many clients (companies, firms, LLPs, proprietorships, individuals, HUFs, trusts).
A **client** is a taxpayer serviced by the organisation; a **user** is a staff member who logs in.

## Non-negotiables

1. Deterministic engines compute every financial and tax figure; AI may extract, classify and explain.
2. Maker-checker: the preparer of a consequential item cannot approve it.
3. No statutory filing or payment without human authorisation and a confirmed external acknowledgement.
4. Mock integrations are always labelled as mock.
5. Every consequential change is auditable; posted financial records are immutable.

## Phases

| Phase | Deliverable | Status |
|---|---|---|
| 0 | Architecture, ERD, PRD/SRS, UI/UX spec, API contracts, backlog | Done (this folder) |
| 1 | Login, users/roles, dashboard, clients + onboarding, document upload/storage, audit log | Done (branch `feature`, pending merge to `develop`) |
| 2 | Accounting engine: CoA, periods, journals, ledgers, trial balance, bank reconciliation, statements, approvals | Planned |
| 3 | GST engine: registrations, registers, GSTR-1, GSTR-2B reconciliation, ITC, GSTR-3B | Planned |
| 4 | Income-tax engine: taxpayer profiles, AIS/TIS/26AS, Form 16, computation, regimes, ITR drafts | Planned |
| 5 | TDS/TCS, AP, AR, fixed assets, inventory, compliance calendar | Planned |
| 6 | AI workforce: orchestrator + 9 agents, Python service | Planned |
| 7 | GSP / ERI / bank integrations (sandbox first) | Planned |
| 8 | Production readiness | Planned |

## External and legal dependencies (owner: business)

| Dependency | Needed for | Notes |
|---|---|---|
| GSP / ASP agreement with GSTN | Phase 7 GST filing APIs | Software alone grants no filing authority |
| Income Tax ERI registration (Type 1/2) | Phase 7 ITR filing | Requires application, sandbox, production approval |
| Approved LLM / OCR provider with Indian data-protection review | Phase 6 | DPDP processing location, retention, contract |
| SMTP / email provider | Notifications, password reset | Phase 2 |
| Official, reviewed tax-rule sources (rates, due dates) | Phases 3–5 | Must be loaded via the rule-review workflow |
| Hosting with encrypted storage and backups | Phase 8 | |
