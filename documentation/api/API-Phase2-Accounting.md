# TAXPILOT AI — API Contracts, Phase 2A (Accounting)

Base URL `/api/v1/accounting`. Money is always a decimal **string** with two places (`"1500.00"`).
Permissions: `ACCOUNTING_READ` (view), `ACCOUNTING_WRITE` (prepare), `ACCOUNTING_APPROVE` (approve, post, close periods).

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | /chart-of-accounts?clientId= | READ | All accounts, groups and ledgers |
| POST | /chart-of-accounts/apply-template | WRITE | `{clientId}`; only when the client has no accounts; template `IN-STANDARD` v1 |
| POST | /accounts · PUT /accounts/{id} | WRITE | Type/group locked once an account has postings; parent must be a group of the same type |
| GET | /periods?clientId= | READ | Monthly periods |
| POST | /financial-years | WRITE | `{clientId, financialYear:"2026-27"}` → 12 periods from the client's FY start month |
| POST | /periods/{id}/close | APPROVE | Refused while journals in the period await approval/posting |
| POST | /periods/{id}/reopen | APPROVE | `{reason}` required, audited |
| GET | /journals?clientId=&status=&from=&to=&q= | READ | Paged |
| POST | /journals | WRITE | Header `Idempotency-Key` (8-80 chars) makes retries safe: repeat → `200` with the original |
| GET / PUT | /journals/{id} | READ / WRITE | PUT only while DRAFT; send `version` |
| POST | /journals/{id}/submit | WRITE | Must be balanced, ≥ 2 lines, period open, client ACTIVE |
| POST | /journals/{id}/approve | APPROVE | `{reason?}`; approver ≠ preparer/submitter |
| POST | /journals/{id}/reject | APPROVE | `{reason}` required; returns to DRAFT |
| POST | /journals/{id}/post | APPROVE | Assigns `JV/<FY>/<00001>`; poster ≠ preparer/submitter; repeat is a no-op |
| POST | /journals/{id}/cancel | WRITE | Not for posted entries |
| POST | /journals/{id}/reverse | WRITE | `{reversalDate, reason}` → new DRAFT reversal that follows the same approval path |
| GET | /ledger?clientId=&accountId=&from=&to= | READ | Opening balance, lines with running balance, closing |
| GET | /trial-balance?clientId=&asOf= | READ | `balanced` flag |
| GET | /profit-loss?clientId=&from=&to= | READ | Grouped by parent group |
| GET | /balance-sheet?clientId=&asOf= | READ | Includes profit for the period; `balanced` + `difference` |

Journal detail responses include `allowedActions` for the current user so the UI only offers permitted actions.
