-- Customers / vendors, GST rate master, invoice series and sales / purchase invoices.

CREATE TABLE parties (
    id               UUID PRIMARY KEY,
    client_id        UUID         NOT NULL REFERENCES clients (id),
    party_type       VARCHAR(10)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    gstin            VARCHAR(15),
    pan              VARCHAR(10),
    state_code       VARCHAR(2),
    email            VARCHAR(254),
    phone            VARCHAR(20),
    address          VARCHAR(500),
    credit_days      SMALLINT     NOT NULL DEFAULT 30 CHECK (credit_days BETWEEN 0 AND 365),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_party_type CHECK (party_type IN ('CUSTOMER','VENDOR','BOTH'))
);
CREATE INDEX ix_parties_client ON parties (client_id, lower(name));
CREATE UNIQUE INDEX ux_parties_gstin ON parties (client_id, gstin) WHERE gstin IS NOT NULL;

-- Party sub-ledger on journal lines (receivable / payable control accounts).
ALTER TABLE journal_entry_lines ADD COLUMN party_id UUID REFERENCES parties (id);
CREATE INDEX ix_journal_lines_party ON journal_entry_lines (party_id) WHERE party_id IS NOT NULL;

-- Versioned GST rate slabs. Statutory data: changes go through tax-expert review (spec Part 23).
CREATE TABLE gst_rate_slabs (
    id               UUID PRIMARY KEY,
    rate             NUMERIC(5,2) NOT NULL,
    effective_from   DATE         NOT NULL,
    effective_to     DATE,
    description      VARCHAR(200) NOT NULL,
    official_source  VARCHAR(300) NOT NULL,
    review_status    VARCHAR(20)  NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewed_by      UUID REFERENCES users (id),
    reviewed_at      TIMESTAMPTZ,
    CONSTRAINT ck_gst_review CHECK (review_status IN ('PENDING_REVIEW','APPROVED','RETIRED'))
);

INSERT INTO gst_rate_slabs (id, rate, effective_from, effective_to, description, official_source) VALUES
    (gen_random_uuid(),  0.00, '2017-07-01', NULL,         'Nil / exempt',                         'CGST Act 2017; rate notifications'),
    (gen_random_uuid(),  0.10, '2017-07-01', NULL,         'Merchant export (special rate)',       'Notification 40/2017-CT(Rate)'),
    (gen_random_uuid(),  0.25, '2017-07-01', NULL,         'Rough diamonds / precious stones',     'Rate notifications'),
    (gen_random_uuid(),  1.50, '2017-07-01', NULL,         'Cut and polished diamonds',            'Rate notifications'),
    (gen_random_uuid(),  3.00, '2017-07-01', NULL,         'Gold, silver, jewellery',              'Rate notifications'),
    (gen_random_uuid(),  5.00, '2017-07-01', NULL,         'Merit rate',                           'Rate notifications; rationalised 22-Sep-2025'),
    (gen_random_uuid(), 12.00, '2017-07-01', '2025-09-21', 'Standard rate (merged into 5%/18%)',   'GST Council 56th meeting, effective 22-Sep-2025'),
    (gen_random_uuid(), 18.00, '2017-07-01', NULL,         'Standard rate',                        'Rate notifications; rationalised 22-Sep-2025'),
    (gen_random_uuid(), 28.00, '2017-07-01', NULL,         'Demerit rate (limited goods after 22-Sep-2025)', 'GST Council 56th meeting'),
    (gen_random_uuid(), 40.00, '2025-09-22', NULL,         'Special demerit rate',                 'GST Council 56th meeting, effective 22-Sep-2025');

-- Outward invoice numbering. GST Rule 46(b): max 16 characters, letters/digits/'-'/'/', unique per FY per GSTIN.
CREATE TABLE invoice_series (
    id                   UUID PRIMARY KEY,
    client_id            UUID         NOT NULL REFERENCES clients (id),
    gst_registration_id  UUID REFERENCES gst_registrations (id),
    prefix               VARCHAR(4)   NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_series_prefix CHECK (prefix ~ '^[A-Z0-9]{1,4}$')
);
CREATE UNIQUE INDEX ux_invoice_series ON invoice_series (client_id, COALESCE(gst_registration_id, '00000000-0000-0000-0000-000000000000'));

CREATE TABLE invoice_sequences (
    series_id        UUID        NOT NULL REFERENCES invoice_series (id),
    financial_year   VARCHAR(7)  NOT NULL,
    next_value       INT         NOT NULL DEFAULT 1,
    PRIMARY KEY (series_id, financial_year)
);

CREATE TABLE invoices (
    id                       UUID PRIMARY KEY,
    organisation_id          UUID          NOT NULL REFERENCES organisations (id),
    client_id                UUID          NOT NULL REFERENCES clients (id),
    invoice_type             VARCHAR(10)   NOT NULL,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    invoice_number           VARCHAR(16),
    supplier_invoice_number  VARCHAR(16),
    invoice_date             DATE          NOT NULL,
    due_date                 DATE,
    party_id                 UUID          NOT NULL REFERENCES parties (id),
    gst_registration_id      UUID REFERENCES gst_registrations (id),
    place_of_supply          VARCHAR(2)    NOT NULL,
    supply_type              VARCHAR(10)   NOT NULL,
    reverse_charge           BOOLEAN       NOT NULL DEFAULT FALSE,
    taxable_total            NUMERIC(19,2) NOT NULL DEFAULT 0,
    cgst_total               NUMERIC(19,2) NOT NULL DEFAULT 0,
    sgst_total               NUMERIC(19,2) NOT NULL DEFAULT 0,
    igst_total               NUMERIC(19,2) NOT NULL DEFAULT 0,
    grand_total              NUMERIC(19,2) NOT NULL DEFAULT 0,
    notes                    VARCHAR(1000),
    journal_entry_id         UUID REFERENCES journal_entries (id),
    prepared_by              UUID          NOT NULL REFERENCES users (id),
    submitted_by             UUID REFERENCES users (id),
    submitted_at             TIMESTAMPTZ,
    approved_by              UUID REFERENCES users (id),
    approved_at              TIMESTAMPTZ,
    self_approved            BOOLEAN       NOT NULL DEFAULT FALSE,
    rejection_reason         VARCHAR(1000),
    idempotency_key          VARCHAR(80),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                  BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_invoice_type CHECK (invoice_type IN ('SALES','PURCHASE')),
    CONSTRAINT ck_invoice_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','POSTED','CANCELLED')),
    CONSTRAINT ck_invoice_supply CHECK (supply_type IN ('INTRA','INTER')),
    CONSTRAINT ck_invoice_number_format CHECK (invoice_number IS NULL OR invoice_number ~ '^[A-Za-z0-9/-]{1,16}$'),
    CONSTRAINT ck_invoice_posted_number CHECK (status <> 'POSTED' OR invoice_type = 'PURCHASE' OR invoice_number IS NOT NULL),
    CONSTRAINT ux_invoice_idempotency UNIQUE (client_id, idempotency_key)
);
CREATE UNIQUE INDEX ux_sales_invoice_number ON invoices (client_id, gst_registration_id, invoice_number)
    WHERE invoice_type = 'SALES' AND invoice_number IS NOT NULL;
CREATE INDEX ix_invoices_client ON invoices (client_id, invoice_type, invoice_date DESC);

CREATE TABLE invoice_lines (
    id               UUID PRIMARY KEY,
    invoice_id       UUID          NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    line_no          SMALLINT      NOT NULL,
    description      VARCHAR(300)  NOT NULL,
    hsn_sac          VARCHAR(8),
    account_id       UUID          NOT NULL REFERENCES ledger_accounts (id),
    quantity         NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
    unit_price       NUMERIC(19,2) NOT NULL CHECK (unit_price >= 0),
    taxable_value    NUMERIC(19,2) NOT NULL,
    gst_rate         NUMERIC(5,2)  NOT NULL,
    cgst             NUMERIC(19,2) NOT NULL DEFAULT 0,
    sgst             NUMERIC(19,2) NOT NULL DEFAULT 0,
    igst             NUMERIC(19,2) NOT NULL DEFAULT 0,
    CONSTRAINT ck_invoice_line_hsn CHECK (hsn_sac IS NULL OR hsn_sac ~ '^[0-9]{4,8}$')
);
CREATE INDEX ix_invoice_lines_invoice ON invoice_lines (invoice_id);

-- Posted invoices are immutable (corrections via credit/debit notes in a later phase).
CREATE FUNCTION invoices_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.status = 'POSTED' THEN
        RAISE EXCEPTION 'Posted invoices cannot be deleted';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'POSTED' AND (
           NEW.status <> 'POSTED' OR NEW.grand_total <> OLD.grand_total OR NEW.taxable_total <> OLD.taxable_total
        OR NEW.invoice_number IS DISTINCT FROM OLD.invoice_number OR NEW.invoice_date <> OLD.invoice_date
        OR NEW.party_id <> OLD.party_id OR NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id) THEN
        RAISE EXCEPTION 'Posted invoices are immutable';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoices_guard BEFORE UPDATE OR DELETE ON invoices
    FOR EACH ROW EXECUTE FUNCTION invoices_guard();

CREATE FUNCTION invoice_lines_guard() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(20);
BEGIN
    SELECT status INTO parent_status FROM invoices
     WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.invoice_id ELSE NEW.invoice_id END;
    IF parent_status = 'POSTED' THEN
        RAISE EXCEPTION 'Lines of a posted invoice are immutable';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoice_lines_guard BEFORE INSERT OR UPDATE OR DELETE ON invoice_lines
    FOR EACH ROW EXECUTE FUNCTION invoice_lines_guard();
