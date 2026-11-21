-- Organisation letterhead (used on reports) and approval policy.

CREATE TABLE organisation_settings (
    organisation_id      UUID PRIMARY KEY REFERENCES organisations (id),
    display_name         VARCHAR(200) NOT NULL,
    legal_name           VARCHAR(200),
    tagline              VARCHAR(200),
    address_line1        VARCHAR(200),
    address_line2        VARCHAR(200),
    city                 VARCHAR(100),
    state_code           VARCHAR(2),
    pincode              VARCHAR(6),
    gstin                VARCHAR(15),
    pan                  VARCHAR(10),
    email                VARCHAR(254),
    phone                VARCHAR(20),
    website              VARCHAR(200),
    report_footer        VARCHAR(500),
    -- When FALSE (default, per spec Part 17.2) the preparer of a journal, invoice or reconciliation can never
    -- approve it. When TRUE the same person may approve; every such approval is flagged in the audit log.
    allow_self_approval  BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by           UUID REFERENCES users (id),
    version              BIGINT       NOT NULL DEFAULT 0
);

INSERT INTO organisation_settings (organisation_id, display_name, tagline, report_footer)
VALUES ('00000000-0000-0000-0000-000000000001', 'TAXPILOT AI',
        'Your Intelligent Accounting & Tax Department',
        'Computer-generated report. Figures are derived from posted entries only.');

INSERT INTO permissions (id, code, module, description) VALUES
    (gen_random_uuid(), 'SETTINGS_WRITE', 'settings', 'Edit organisation letterhead and approval policy');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'SETTINGS_WRITE' WHERE r.code = 'ADMIN';
