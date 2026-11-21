# API — Phase 2B part 2: reports, notes, year-end close, exports

All endpoints are under `/api/v1`, need a bearer token, and return RFC 7807 problem details on error
(`code`, `detail`, `errors[]`). Money is a decimal string with two places. Dates are ISO `yyyy-MM-dd`.

## Reports (permission `ACCOUNTING_READ`)

| Method | Path | Notes |
|---|---|---|
| GET | `/accounting/profit-loss?clientId&from&to[&compareFrom&compareTo]` | Adds `previous` on each line, `previousTotal` per section, `previousTotalIncome/Expenses/NetProfit` when both compare dates are given |
| GET | `/accounting/balance-sheet?clientId&asOf[&compareAsOf]` | Adds the `previous*` totals as above |
| GET | `/accounting/cash-flow?clientId&from&to` | Indirect method: `netProfit`, `nonCashAdjustments`, `workingCapital`, `operatingTotal`, `investing`, `financing`, `netChange`, `openingCash`, `closingCash`, `reconciles`, `unclassifiedAccounts` |
| GET | `/accounting/ageing?clientId&kind=RECEIVABLE\|PAYABLE&asOf` | Rows per party: `outstanding`, five `buckets` (Current, 1-30, 31-60, 61-90, 90+ days past due), `unlinked`, `advance`; totals; `controlBalance`, `reconciles` |
| GET | `/accounting/registers?clientId&kind=SALES\|PURCHASE&from&to` | Posted invoices; credit notes (sales) and debit notes (purchase) are negative |
| GET | `/accounting/export?report&format=pdf\|xlsx&clientId&…` | `report` = `TRIAL_BALANCE`, `PROFIT_LOSS`, `BALANCE_SHEET`, `CASH_FLOW`, `LEDGER`, `AGEING_RECEIVABLE`, `AGEING_PAYABLE`, `SALES_REGISTER`, `PURCHASE_REGISTER`; takes the same date/compare/`accountId` parameters as the screen. Returns a file (`Content-Disposition: attachment`). Audited as `REPORT_EXPORTED`. Missing parameter → 400 `MISSING_PARAMETER` |

Cash-flow classification uses `cashFlowCategory` on each balance-sheet ledger (`CASH`, `OPERATING`, `NON_CASH`,
`INVESTING`, `FINANCING`), defaulted from type/subtype and editable through `POST/PUT /accounting/accounts`.
Year-end closing entries are excluded from P&L and cash flow.

## Year-end close

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/accounting/financial-years/{fy}/prepare-close` body `{clientId}` | `ACCOUNTING_WRITE` | Creates a DRAFT journal of type `CLOSING`, dated on the last day of the year, zeroing every income and expense ledger against retained earnings (3200). 409 `ALREADY_CLOSING`, `UNPOSTED_JOURNALS`, `PERIOD_CLOSED`, `NOTHING_TO_CLOSE` |

The closing journal is then submitted, approved and posted with the normal journal endpoints. Posting re-checks the
balances (409 `STALE_CLOSING` if anything changed — cancel and prepare again), closes all periods of the year and
marks them `yearClosed`; reopening such a period → 409 `YEAR_CLOSED`. A closing journal cannot be edited or reversed,
and `CLOSING` cannot be chosen as an entry type on create.

## Recurring journals

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/accounting/recurring?clientId` | `ACCOUNTING_READ` | Templates |
| POST | `/accounting/recurring` | `ACCOUNTING_WRITE` | `{clientId, name, narration, reference?, frequency: MONTHLY\|QUARTERLY\|YEARLY, nextDate, endDate?, active?, lines[]}` → 201 |
| PUT | `/accounting/recurring/{id}` | `ACCOUNTING_WRITE` | Same body plus `version` |
| POST | `/accounting/recurring/generate` | `ACCOUNTING_WRITE` | `{clientId, asOf}` → `{createdJournalIds[], skipped[]}`. Creates DRAFT journals for every due date (idempotency key per template and date, so reruns create nothing new); dates in a closed period or without an open year are listed in `skipped` |

## Party lines

Journal lines (`lines[].partyId`) and the bank "create journal" request (`partyId`) accept a customer/vendor, only on
receivable or payable ledgers and only for the same client (400 on `lines[i].partyId`). Ageing uses these.

## Credit and debit notes

`POST /invoices` with `invoiceType` `CREDIT_NOTE` (against a posted SALES invoice) or `DEBIT_NOTE` (against a posted
PURCHASE invoice), plus `originalInvoiceId` and `noteReason`. Party, GSTIN, place of supply, supply type and reverse
charge are taken from the original; the note date cannot be before it; the total cannot exceed the original's
`noteableBalance` (checked on save and again on approval, 409 `NOTE_EXCEEDS_INVOICE`). Numbering uses separate series:
`CN/26-27/00001`, `DN/26-27/00001` (`GET /invoices/series` now returns `kind`). Invoice responses add
`originalInvoiceId`, `originalNumber`, `noteReason`, `notesPostedTotal`, `noteableBalance`, and `allowedActions` may
include `CREDIT_NOTE` / `DEBIT_NOTE`.

## Printable document

`GET /invoices/{id}/pdf` (`ACCOUNTING_READ`) — tax invoice, credit note or debit note with the Rule 46 particulars,
amount in words and signatory block. Anything not posted is watermarked "DRAFT — NOT A TAX INVOICE". Purchase
invoices → 400 `NOT_OUR_DOCUMENT`. Audited as `INVOICE_PDF_DOWNLOADED`.

## GST rate review

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/settings/gst-rates` | any signed-in user | All slabs with `reviewStatus`, `reviewedBy`, `reviewedAt` |
| POST | `/settings/gst-rates/{id}/approve` | `TAX_RULES_APPROVE` (Admin, Partner) | PENDING_REVIEW → APPROVED; 409 `ALREADY_REVIEWED`. Audited as `GST_RATE_APPROVED` |
