-- Phase 2B part 2: cash-flow classification, year-end close, recurring journals, credit/debit notes,
-- GST rate review permission.

-- ---------------------------------------------------------------- cash-flow classification
ALTER TABLE ledger_accounts ADD COLUMN cash_flow_category VARCHAR(20);
ALTER TABLE ledger_accounts ADD CONSTRAINT ck_cash_flow_category CHECK (cash_flow_category IS NULL OR cash_flow_category IN
    ('CASH','OPERATING','NON_CASH','INVESTING','FINANCING'));

UPDATE ledger_accounts SET cash_flow_category = CASE
    WHEN is_group THEN NULL
    WHEN subtype IN ('CASH','BANK') THEN 'CASH'
    WHEN account_type IN ('INCOME','EXPENSE') THEN NULL
    WHEN subtype = 'CONTRA_ASSET' THEN 'NON_CASH'
    WHEN subtype = 'FIXED_ASSET' OR template_code IN ('IN-STANDARD:1230','IN-STANDARD:1240') THEN 'INVESTING'
    WHEN account_type = 'EQUITY' OR template_code IN ('IN-STANDARD:2170','IN-STANDARD:2210') THEN 'FINANCING'
    ELSE 'OPERATING' END;

-- ---------------------------------------------------------------- year-end closing entries
ALTER TABLE journal_entries DROP CONSTRAINT ck_journal_type;
ALTER TABLE journal_entries ADD CONSTRAINT ck_journal_type
    CHECK (entry_type IN ('GENERAL','OPENING','ADJUSTMENT','REVERSAL','CLOSING'));
ALTER TABLE journal_entries ADD COLUMN closing_financial_year VARCHAR(7);
CREATE UNIQUE INDEX ux_journal_closing_year ON journal_entries (client_id, closing_financial_year)
    WHERE closing_financial_year IS NOT NULL AND status <> 'CANCELLED';

-- ---------------------------------------------------------------- recurring journals
CREATE TABLE recurring_journals (
    id            UUID PRIMARY KEY,
    client_id     UUID          NOT NULL REFERENCES clients (id),
    name          VARCHAR(120)  NOT NULL,
    narration     VARCHAR(1000) NOT NULL,
    reference     VARCHAR(100),
    frequency     VARCHAR(10)   NOT NULL,
    next_date     DATE          NOT NULL,
    end_date      DATE,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by    UUID          NOT NULL REFERENCES users (id),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_recurring_frequency CHECK (frequency IN ('MONTHLY','QUARTERLY','YEARLY'))
);
CREATE INDEX ix_recurring_client ON recurring_journals (client_id, next_date);

CREATE TABLE recurring_journal_lines (
    id                    UUID PRIMARY KEY,
    recurring_journal_id  UUID          NOT NULL REFERENCES recurring_journals (id) ON DELETE CASCADE,
    line_no               SMALLINT      NOT NULL,
    account_id            UUID          NOT NULL REFERENCES ledger_accounts (id),
    party_id              UUID REFERENCES parties (id),
    debit                 NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit                NUMERIC(19,2) NOT NULL DEFAULT 0,
    description           VARCHAR(500),
    CONSTRAINT ck_recurring_line_side CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
);

-- ---------------------------------------------------------------- credit / debit notes
ALTER TABLE invoices ALTER COLUMN invoice_type TYPE VARCHAR(20);
ALTER TABLE invoices DROP CONSTRAINT ck_invoice_type;
ALTER TABLE invoices ADD CONSTRAINT ck_invoice_type CHECK (invoice_type IN ('SALES','PURCHASE','CREDIT_NOTE','DEBIT_NOTE'));
ALTER TABLE invoices ADD COLUMN original_invoice_id UUID REFERENCES invoices (id);
ALTER TABLE invoices ADD COLUMN note_reason VARCHAR(300);
ALTER TABLE invoices DROP CONSTRAINT ck_invoice_posted_number;
ALTER TABLE invoices ADD CONSTRAINT ck_invoice_posted_number
    CHECK (status <> 'POSTED' OR invoice_type = 'PURCHASE' OR invoice_number IS NOT NULL);
ALTER TABLE invoices ADD CONSTRAINT ck_invoice_note_original
    CHECK (invoice_type NOT IN ('CREDIT_NOTE','DEBIT_NOTE') OR original_invoice_id IS NOT NULL);
DROP INDEX ux_sales_invoice_number;
CREATE UNIQUE INDEX ux_outward_document_number ON invoices (client_id, gst_registration_id, invoice_type, invoice_number)
    WHERE invoice_type <> 'PURCHASE' AND invoice_number IS NOT NULL;

-- Separate number series per document kind (INV / CN / DN) and GSTIN.
ALTER TABLE invoice_series ADD COLUMN kind VARCHAR(12) NOT NULL DEFAULT 'INVOICE';
ALTER TABLE invoice_series ADD CONSTRAINT ck_series_kind CHECK (kind IN ('INVOICE','CREDIT_NOTE','DEBIT_NOTE'));
DROP INDEX ux_invoice_series;
CREATE UNIQUE INDEX ux_invoice_series ON invoice_series
    (client_id, COALESCE(gst_registration_id, '00000000-0000-0000-0000-000000000000'), kind);

-- ---------------------------------------------------------------- GST rate review
INSERT INTO permissions (id, code, module, description) VALUES
    (gen_random_uuid(), 'TAX_RULES_APPROVE', 'settings', 'Review and approve statutory rate tables (e.g. GST slabs)');
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'TAX_RULES_APPROVE' WHERE r.code IN ('ADMIN','PARTNER');
