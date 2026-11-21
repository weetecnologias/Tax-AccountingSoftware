-- Client master and sub-records.

CREATE TABLE clients (
    id                      UUID PRIMARY KEY,
    organisation_id         UUID         NOT NULL REFERENCES organisations (id),
    client_code             VARCHAR(20)  NOT NULL,
    display_name            VARCHAR(200) NOT NULL,
    legal_name              VARCHAR(200) NOT NULL,
    entity_type             VARCHAR(30)  NOT NULL,
    pan_encrypted           BYTEA        NOT NULL,
    pan_hash                VARCHAR(64)  NOT NULL,
    pan_last4               VARCHAR(4)   NOT NULL,
    tan                     VARCHAR(10),
    date_of_incorporation   DATE,
    email                   VARCHAR(254),
    phone                   VARCHAR(20),
    fy_start_month          SMALLINT     NOT NULL DEFAULT 4 CHECK (fy_start_month BETWEEN 1 AND 12),
    accounting_method       VARCHAR(20)  NOT NULL DEFAULT 'ACCRUAL',
    status                  VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    accountant_id           UUID REFERENCES users (id),
    reviewer_id             UUID REFERENCES users (id),
    notes                   VARCHAR(2000),
    closure_reason          VARCHAR(1000),
    closed_at               TIMESTAMPTZ,
    created_by              UUID         NOT NULL REFERENCES users (id),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_clients_code UNIQUE (organisation_id, client_code),
    CONSTRAINT ux_clients_pan  UNIQUE (organisation_id, pan_hash),
    CONSTRAINT ck_clients_status CHECK (status IN
        ('DRAFT','DOCUMENTS_PENDING','UNDER_VERIFICATION','READY_FOR_APPROVAL','ACTIVE','ON_HOLD','CLOSED')),
    CONSTRAINT ck_clients_method CHECK (accounting_method IN ('ACCRUAL','CASH'))
);
CREATE INDEX ix_clients_status ON clients (organisation_id, status);
CREATE INDEX ix_clients_name ON clients (organisation_id, lower(display_name));

CREATE SEQUENCE client_code_seq START 1001;

CREATE TABLE client_status_history (
    id           UUID PRIMARY KEY,
    client_id    UUID        NOT NULL REFERENCES clients (id),
    from_status  VARCHAR(30),
    to_status    VARCHAR(30) NOT NULL,
    changed_by   UUID        NOT NULL REFERENCES users (id),
    comment      VARCHAR(1000),
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_client_status_history_client ON client_status_history (client_id, changed_at DESC);

CREATE TABLE client_contacts (
    id           UUID PRIMARY KEY,
    client_id    UUID         NOT NULL REFERENCES clients (id),
    name         VARCHAR(200) NOT NULL,
    designation  VARCHAR(120),
    email        VARCHAR(254),
    phone        VARCHAR(20),
    is_primary   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_client_contacts_client ON client_contacts (client_id);

CREATE TABLE client_addresses (
    id            UUID PRIMARY KEY,
    client_id     UUID         NOT NULL REFERENCES clients (id),
    address_type  VARCHAR(30)  NOT NULL,
    line1         VARCHAR(200) NOT NULL,
    line2         VARCHAR(200),
    city          VARCHAR(100) NOT NULL,
    state_code    VARCHAR(2)   NOT NULL,
    pincode       VARCHAR(6)   NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_client_addresses_client ON client_addresses (client_id);

CREATE TABLE gst_registrations (
    id                 UUID PRIMARY KEY,
    client_id          UUID         NOT NULL REFERENCES clients (id),
    gstin              VARCHAR(15)  NOT NULL UNIQUE,
    trade_name         VARCHAR(200),
    state_code         VARCHAR(2)   NOT NULL,
    registration_type  VARCHAR(30)  NOT NULL,
    effective_date     DATE         NOT NULL,
    filing_frequency   VARCHAR(20)  NOT NULL,
    principal_place    VARCHAR(500),
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    cancelled_on       DATE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_gst_status CHECK (status IN ('ACTIVE','SUSPENDED','CANCELLED'))
);
CREATE INDEX ix_gst_registrations_client ON gst_registrations (client_id);

CREATE TABLE bank_accounts (
    id                        UUID PRIMARY KEY,
    client_id                 UUID         NOT NULL REFERENCES clients (id),
    bank_name                 VARCHAR(120) NOT NULL,
    branch                    VARCHAR(120),
    account_holder            VARCHAR(200) NOT NULL,
    account_number_encrypted  BYTEA        NOT NULL,
    account_number_last4      VARCHAR(4)   NOT NULL,
    ifsc                      VARCHAR(11)  NOT NULL,
    account_type              VARCHAR(20)  NOT NULL,
    active                    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_bank_accounts_client ON bank_accounts (client_id);
