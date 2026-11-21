# TAXPILOT AI — API Contracts, Phase 2B part 1

Money is a decimal string with two places. Quantities allow three decimals; GST rates two.

## Organisation settings — `/api/v1/settings/organisation`
| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | / | authenticated | Letterhead fields and `allowSelfApproval` |
| GET | /letterhead | authenticated | Formatted block used on reports |
| PUT | / | SETTINGS_WRITE (ADMIN) | Send `version`. Changing `allowSelfApproval` is audited as `APPROVAL_POLICY_CHANGED` |

**Approval policy.** `allowSelfApproval = false` (default): the preparer/submitter of a journal or invoice, or the
importer of a bank statement, cannot approve/post/sign it off (`403 MAKER_CHECKER`). `true`: the same person may,
but approval remains a separate step and every self-approval is recorded with `selfApproved: true`.

## Customers and vendors — `/api/v1/parties`
| GET `?clientId=` | ACCOUNTING_READ | | POST / PUT `/{id}` | ACCOUNTING_WRITE |
GSTIN validated (checksum, state); state is taken from the GSTIN; PAN derived from the GSTIN when not given.

## Invoices — `/api/v1/invoices`
| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | ?clientId=&type=&status=&q= | READ | Paged |
| GET | /{id} | READ | Includes lines and `allowedActions` |
| GET | /gst-rates?date= | READ | Slabs effective on the date (versioned table, pending expert review) |
| GET | /series?clientId= · PUT /series/{id} `{prefix}` | READ · APPROVE | One series per GSTIN; prefix 1–4 chars |
| POST | / | WRITE | `Idempotency-Key` supported. Sales lines → income accounts; purchase lines → expense/asset |
| PUT | /{id} | WRITE | Drafts only, with `version` |
| POST | /{id}/submit · /approve · /reject `{reason}` · /cancel | WRITE · APPROVE · APPROVE · WRITE | `approve` = approve and post in one step |

Numbering: `PREFIX/YY-YY/NNNNN` (e.g. `INV/26-27/00001`) allocated at approval, gap-free per series and FY,
≤ 16 characters (CGST Rule 46(b)). Purchase invoices keep the supplier's number; duplicates per vendor and FY are
refused (`409 DUPLICATE_INVOICE`).

Posting: Sales — Dr Trade Receivables (party) / Cr income / Cr Output CGST+SGST or IGST. Purchase — Dr expense or
asset / Dr Input CGST+SGST or IGST (when received at our GSTIN; otherwise tax is added to cost) / Cr Trade Payables
(party). Reverse charge — tax credited to "GST payable under reverse charge" instead of the vendor.

## Bank reconciliation — `/api/v1/bank-reconciliation`
| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | /statements?clientId= | READ | |
| POST | /statements (multipart: file, clientId, ledgerAccountId) | WRITE | CSV/XLS/XLSX ≤ 10 MB; same file twice → `409 DUPLICATE_STATEMENT`; overlapping rows skipped |
| GET | /statements/{id} · /transactions · /summary | READ | `summary` = bank reconciliation statement |
| POST | /statements/{id}/auto-match | WRITE | Applies only unique exact matches |
| DELETE | /statements/{id} | WRITE | Discard an unsigned import (audited) |
| POST | /statements/{id}/approve `{reason?}` | APPROVE | Requires zero open lines; maker-checker vs importer |
| GET | /transactions/{id}/suggestions | READ | PROBABLE (same amount ≤ 10 days) and PARTIAL (≤ ₹1 difference) with confidence and evidence |
| POST | /transactions/{id}/match `{lineId}` · /unmatch · /exclude `{reason}` | WRITE | One-to-one; direction must agree |
| POST | /transactions/{id}/create-journal `{counterAccountId, narration?}` | WRITE | Creates a DRAFT journal (never posts) |

Exact match = same amount and direction, entry within ±3 days, and the same date or a shared reference token
(cheque/UTR/invoice number), with exactly one candidate.
