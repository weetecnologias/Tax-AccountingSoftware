# Sample bank statements — SYNTHETIC TEST DATA

These files are **made up for development and testing**. They are not real bank statements, contain no real
customer data, and were not downloaded from any bank. The account numbers are fictitious and masked.

Each file holds the same five transactions for the demo client *Sharma Textiles* (April–May 2026), written in the
column layout commonly seen in that bank's internet-banking export. Real exports change over time and vary by
channel (CSV, XLS, PDF), so the importer locates columns by header name rather than by fixed position.

| File | Layout modelled on | Notable traits |
|---|---|---|
| `hdfc-sample.csv` | HDFC Bank | `Chq./Ref.No.`, `Withdrawal Amt.` / `Deposit Amt.`, dates `dd/MM/yy` |
| `icici-sample.csv` | ICICI Bank | `Transaction Remarks`, amounts in `(INR )` columns, dates `dd/MM/yyyy` |
| `sbi-sample.csv` | State Bank of India | `Txn Date`, `Ref No./Cheque No.`, dates `1 Apr 2026` |
| `axis-sample.csv` | Axis Bank | `Tran Date`, `PARTICULARS`, `DR` / `CR` / `BAL`, newest-first order |
| `kotak-sample.csv` | Kotak Mahindra Bank | single `Amount` column with `Dr / Cr` indicator |

Transactions (opening balance 0.00):

| Date | Description | Withdrawal | Deposit | Balance | Books |
|---|---|---|---|---|---|
| 01-04-2026 | Capital from promoters | | 5,00,000.00 | 5,00,000.00 | JV 1 (same day) → exact match |
| 05-04-2026 | SMS alert charges incl. GST | 118.00 | | 4,99,882.00 | not in books → draft a journal |
| 20-04-2026 | Receipt from Mehta Garments ST/26-27/001 | | 2,36,000.00 | 7,35,882.00 | no receipt booked yet |
| 03-05-2026 | Rent for April | 45,000.00 | | 6,90,882.00 | JV 3 on 01-05 → probable match |
| 31-05-2026 | Savings interest credit | | 1,250.00 | 6,92,132.00 | not in books |
