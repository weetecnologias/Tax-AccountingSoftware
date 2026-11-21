-- Bank statement imports, normalised bank transactions and reconciliation.

CREATE TABLE bank_statement_imports (
    id                  UUID PRIMARY KEY,
    client_id           UUID          NOT NULL REFERENCES clients (id),
    ledger_account_id   UUID          NOT NULL REFERENCES ledger_accounts (id),
    bank_format         VARCHAR(20)   NOT NULL,
    original_filename   VARCHAR(255)  NOT NULL,
    sha256              VARCHAR(64)   NOT NULL,
    period_from         DATE          NOT NULL,
    period_to           DATE          NOT NULL,
    opening_balance     NUMERIC(19,2),
    closing_balance     NUMERIC(19,2),
    total_deposits      NUMERIC(19,2) NOT NULL,
    total_withdrawals   NUMERIC(19,2) NOT NULL,
    line_count          INT           NOT NULL,
    duplicate_count     INT           NOT NULL DEFAULT 0,
    status              VARCHAR(20)   NOT NULL DEFAULT 'IN_PROGRESS',
    imported_by         UUID          NOT NULL REFERENCES users (id),
    imported_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    approved_by         UUID REFERENCES users (id),
    approved_at         TIMESTAMPTZ,
    approval_comment    VARCHAR(1000),
    self_approved       BOOLEAN       NOT NULL DEFAULT FALSE,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_bank_import_status CHECK (status IN ('IN_PROGRESS','RECONCILED')),
    CONSTRAINT ux_bank_import_file UNIQUE (ledger_account_id, sha256)
);
CREATE INDEX ix_bank_imports_client ON bank_statement_imports (client_id, imported_at DESC);

CREATE TABLE bank_transactions (
    id                  UUID PRIMARY KEY,
    import_id           UUID          NOT NULL REFERENCES bank_statement_imports (id),
    client_id           UUID          NOT NULL REFERENCES clients (id),
    ledger_account_id   UUID          NOT NULL REFERENCES ledger_accounts (id),
    row_no              INT           NOT NULL,
    txn_date            DATE          NOT NULL,
    value_date          DATE,
    description         VARCHAR(500)  NOT NULL,
    reference           VARCHAR(100),
    withdrawal          NUMERIC(19,2) NOT NULL DEFAULT 0,
    deposit             NUMERIC(19,2) NOT NULL DEFAULT 0,
    balance             NUMERIC(19,2),
    fingerprint         VARCHAR(64)   NOT NULL,
    category_hint       VARCHAR(30),
    status              VARCHAR(20)   NOT NULL DEFAULT 'UNMATCHED',
    match_type          VARCHAR(20),
    match_confidence    SMALLINT,
    matched_line_id     UUID REFERENCES journal_entry_lines (id),
    matched_by          UUID REFERENCES users (id),
    matched_at          TIMESTAMPTZ,
    exclusion_reason    VARCHAR(300),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_bank_txn_amount CHECK (withdrawal >= 0 AND deposit >= 0 AND (withdrawal > 0) <> (deposit > 0)),
    CONSTRAINT ck_bank_txn_status CHECK (status IN ('UNMATCHED','MATCHED','EXCLUDED')),
    CONSTRAINT ck_bank_txn_match CHECK (status <> 'MATCHED' OR matched_line_id IS NOT NULL)
);
CREATE INDEX ix_bank_txn_import ON bank_transactions (import_id, row_no);
CREATE INDEX ix_bank_txn_account ON bank_transactions (ledger_account_id, txn_date);
-- A ledger line can reconcile against at most one bank transaction.
CREATE UNIQUE INDEX ux_bank_txn_matched_line ON bank_transactions (matched_line_id) WHERE matched_line_id IS NOT NULL;
-- Overlapping statements: the same transaction is imported once per bank ledger.
CREATE UNIQUE INDEX ux_bank_txn_fingerprint ON bank_transactions (ledger_account_id, fingerprint);
