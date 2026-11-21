-- Document storage metadata. File bytes live (encrypted) in object storage.

CREATE TABLE documents (
    id                 UUID PRIMARY KEY,
    organisation_id    UUID          NOT NULL REFERENCES organisations (id),
    client_id          UUID          NOT NULL REFERENCES clients (id),
    document_type      VARCHAR(40)   NOT NULL,
    original_filename  VARCHAR(255)  NOT NULL,
    content_type       VARCHAR(100)  NOT NULL,
    size_bytes         BIGINT        NOT NULL,
    sha256             VARCHAR(64)   NOT NULL,
    storage_key        VARCHAR(200)  NOT NULL UNIQUE,
    financial_year     VARCHAR(7),
    status             VARCHAR(30)   NOT NULL,
    scan_status        VARCHAR(20)   NOT NULL,
    duplicate_of_id    UUID REFERENCES documents (id),
    uploaded_by        UUID          NOT NULL REFERENCES users (id),
    uploaded_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    reviewed_by        UUID REFERENCES users (id),
    reviewed_at        TIMESTAMPTZ,
    review_comment     VARCHAR(1000),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_documents_status CHECK (status IN
        ('UPLOADED','DUPLICATE_SUSPECTED','REVIEW_REQUIRED','APPROVED','REJECTED','ARCHIVED')),
    CONSTRAINT ck_documents_scan CHECK (scan_status IN ('CLEAN','NOT_SCANNED')),
    CONSTRAINT ck_documents_fy CHECK (financial_year IS NULL OR financial_year ~ '^[0-9]{4}-[0-9]{2}$')
);
CREATE INDEX ix_documents_client ON documents (client_id, uploaded_at DESC);
CREATE INDEX ix_documents_sha ON documents (client_id, sha256);
CREATE INDEX ix_documents_status ON documents (organisation_id, status);
