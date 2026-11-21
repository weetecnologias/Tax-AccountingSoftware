-- Phase 2A: double-entry accounting engine.
-- All money is NUMERIC(19,2). Integrity rules are enforced here as well as in the application.

CREATE TABLE ledger_accounts (
    id               UUID PRIMARY KEY,
    organisation_id  UUID         NOT NULL REFERENCES organisations (id),
    client_id        UUID         NOT NULL REFERENCES clients (id),
    code             VARCHAR(20)  NOT NULL,
    name             VARCHAR(150) NOT NULL,
    account_type     VARCHAR(20)  NOT NULL,
    subtype          VARCHAR(30),
    parent_id        UUID REFERENCES ledger_accounts (id),
    is_group         BOOLEAN      NOT NULL DEFAULT FALSE,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    template_code    VARCHAR(40),
    description      VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_ledger_accounts_code UNIQUE (client_id, code),
    CONSTRAINT ck_ledger_accounts_type CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE'))
);
CREATE INDEX ix_ledger_accounts_client ON ledger_accounts (client_id, code);
CREATE INDEX ix_ledger_accounts_parent ON ledger_accounts (parent_id);

CREATE TABLE accounting_periods (
    id               UUID PRIMARY KEY,
    client_id        UUID         NOT NULL REFERENCES clients (id),
    financial_year   VARCHAR(7)   NOT NULL,
    period_number    SMALLINT     NOT NULL CHECK (period_number BETWEEN 1 AND 12),
    start_date       DATE         NOT NULL,
    end_date         DATE         NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    closed_by        UUID REFERENCES users (id),
    closed_at        TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_periods UNIQUE (client_id, financial_year, period_number),
    CONSTRAINT ux_periods_start UNIQUE (client_id, start_date),
    CONSTRAINT ck_periods_status CHECK (status IN ('OPEN','CLOSED')),
    CONSTRAINT ck_periods_dates CHECK (end_date >= start_date)
);
CREATE INDEX ix_periods_client_dates ON accounting_periods (client_id, start_date, end_date);

CREATE TABLE journal_sequences (
    client_id        UUID        NOT NULL REFERENCES clients (id),
    financial_year   VARCHAR(7)  NOT NULL,
    next_value       INT         NOT NULL DEFAULT 1,
    PRIMARY KEY (client_id, financial_year)
);

CREATE TABLE journal_entries (
    id               UUID PRIMARY KEY,
    organisation_id  UUID          NOT NULL REFERENCES organisations (id),
    client_id        UUID          NOT NULL REFERENCES clients (id),
    entry_number     VARCHAR(30),
    entry_date       DATE          NOT NULL,
    period_id        UUID          NOT NULL REFERENCES accounting_periods (id),
    entry_type       VARCHAR(20)   NOT NULL DEFAULT 'GENERAL',
    status           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    narration        VARCHAR(1000) NOT NULL,
    reference        VARCHAR(100),
    total_debit      NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_credit     NUMERIC(19,2) NOT NULL DEFAULT 0,
    prepared_by      UUID          NOT NULL REFERENCES users (id),
    submitted_by     UUID REFERENCES users (id),
    submitted_at     TIMESTAMPTZ,
    approved_by      UUID REFERENCES users (id),
    approved_at      TIMESTAMPTZ,
    approval_comment VARCHAR(1000),
    posted_by        UUID REFERENCES users (id),
    posted_at        TIMESTAMPTZ,
    rejected_by      UUID REFERENCES users (id),
    rejected_at      TIMESTAMPTZ,
    rejection_reason VARCHAR(1000),
    reversal_of_id   UUID REFERENCES journal_entries (id),
    reversed_by_id   UUID REFERENCES journal_entries (id),
    idempotency_key  VARCHAR(80),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ux_journal_number UNIQUE (client_id, entry_number),
    CONSTRAINT ux_journal_idempotency UNIQUE (client_id, idempotency_key),
    CONSTRAINT ck_journal_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','POSTED','CANCELLED')),
    CONSTRAINT ck_journal_type CHECK (entry_type IN ('GENERAL','OPENING','ADJUSTMENT','REVERSAL')),
    CONSTRAINT ck_journal_totals CHECK (total_debit >= 0 AND total_credit >= 0),
    CONSTRAINT ck_journal_posted_number CHECK (status <> 'POSTED' OR entry_number IS NOT NULL)
);
CREATE INDEX ix_journal_client_date ON journal_entries (client_id, entry_date DESC);
CREATE INDEX ix_journal_client_status ON journal_entries (client_id, status);

CREATE TABLE journal_entry_lines (
    id                UUID PRIMARY KEY,
    journal_entry_id  UUID          NOT NULL REFERENCES journal_entries (id) ON DELETE CASCADE,
    line_no           SMALLINT      NOT NULL,
    account_id        UUID          NOT NULL REFERENCES ledger_accounts (id),
    debit             NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit            NUMERIC(19,2) NOT NULL DEFAULT 0,
    description       VARCHAR(500),
    CONSTRAINT ck_line_non_negative CHECK (debit >= 0 AND credit >= 0),
    CONSTRAINT ck_line_one_side CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
);
CREATE INDEX ix_journal_lines_entry ON journal_entry_lines (journal_entry_id);
CREATE INDEX ix_journal_lines_account ON journal_entry_lines (account_id);

-- ---------------------------------------------------------------------------------------------
-- Integrity triggers
-- ---------------------------------------------------------------------------------------------

-- Posting: lines must balance, be non-empty, hit only active non-group accounts of the same client,
-- and the period must be open. Posted entries are immutable except for linking a reversal.
CREATE FUNCTION journal_entries_guard() RETURNS trigger AS $$
DECLARE
    dr NUMERIC(19,2);
    cr NUMERIC(19,2);
    n  INT;
    bad INT;
    period_status VARCHAR(10);
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'POSTED' THEN
            RAISE EXCEPTION 'Posted journal entries cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.status = 'POSTED' THEN
        IF NEW.status <> 'POSTED'
           OR NEW.entry_number IS DISTINCT FROM OLD.entry_number
           OR NEW.entry_date <> OLD.entry_date
           OR NEW.period_id <> OLD.period_id
           OR NEW.client_id <> OLD.client_id
           OR NEW.total_debit <> OLD.total_debit
           OR NEW.total_credit <> OLD.total_credit
           OR NEW.narration <> OLD.narration
           OR NEW.entry_type <> OLD.entry_type
           OR NEW.reversal_of_id IS DISTINCT FROM OLD.reversal_of_id
           OR (OLD.reversed_by_id IS NOT NULL AND NEW.reversed_by_id IS DISTINCT FROM OLD.reversed_by_id) THEN
            RAISE EXCEPTION 'Posted journal entries are immutable; use a reversal entry';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status = 'POSTED' THEN
        SELECT COALESCE(SUM(debit), 0), COALESCE(SUM(credit), 0), COUNT(*)
          INTO dr, cr, n
          FROM journal_entry_lines WHERE journal_entry_id = NEW.id;
        IF n < 2 THEN
            RAISE EXCEPTION 'A journal entry needs at least two lines to post';
        END IF;
        IF dr <> cr OR dr = 0 THEN
            RAISE EXCEPTION 'Journal entry is not balanced (debit %, credit %)', dr, cr;
        END IF;
        IF dr <> NEW.total_debit OR cr <> NEW.total_credit THEN
            RAISE EXCEPTION 'Journal totals do not match its lines';
        END IF;
        SELECT COUNT(*) INTO bad
          FROM journal_entry_lines l JOIN ledger_accounts a ON a.id = l.account_id
         WHERE l.journal_entry_id = NEW.id
           AND (a.client_id <> NEW.client_id OR a.is_group OR NOT a.active);
        IF bad > 0 THEN
            RAISE EXCEPTION 'Journal lines must use active, non-group accounts of the same client';
        END IF;
        SELECT status INTO period_status FROM accounting_periods
         WHERE id = NEW.period_id AND client_id = NEW.client_id
           AND NEW.entry_date BETWEEN start_date AND end_date;
        IF period_status IS NULL THEN
            RAISE EXCEPTION 'Entry date is outside its accounting period';
        END IF;
        IF period_status <> 'OPEN' THEN
            RAISE EXCEPTION 'Cannot post into a closed accounting period';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_guard
    BEFORE INSERT OR UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION journal_entries_guard();

-- Lines of a posted entry can never be added, changed or removed.
CREATE FUNCTION journal_lines_guard() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(20);
    entry_id UUID;
BEGIN
    entry_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.journal_entry_id ELSE NEW.journal_entry_id END;
    SELECT status INTO parent_status FROM journal_entries WHERE id = entry_id;
    IF parent_status = 'POSTED' THEN
        RAISE EXCEPTION 'Lines of a posted journal entry are immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.journal_entry_id <> NEW.journal_entry_id THEN
        RAISE EXCEPTION 'Journal lines cannot move between entries';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_lines_guard
    BEFORE INSERT OR UPDATE OR DELETE ON journal_entry_lines
    FOR EACH ROW EXECUTE FUNCTION journal_lines_guard();
