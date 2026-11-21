-- Reference data: the single organisation, permission catalogue and system roles.
-- No user accounts are seeded here; the first admin is bootstrapped from environment variables.

INSERT INTO organisations (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'TAXPILOT Internal Accounting');

INSERT INTO permissions (id, code, module, description) VALUES
    (gen_random_uuid(), 'DASHBOARD_READ',  'dashboard', 'View dashboard metrics'),
    (gen_random_uuid(), 'USER_READ',       'users',     'View users'),
    (gen_random_uuid(), 'USER_WRITE',      'users',     'Create, edit, deactivate users and reset passwords'),
    (gen_random_uuid(), 'ROLE_READ',       'users',     'View roles and permissions'),
    (gen_random_uuid(), 'ROLE_WRITE',      'users',     'Create and edit roles'),
    (gen_random_uuid(), 'CLIENT_READ',     'clients',   'View clients'),
    (gen_random_uuid(), 'CLIENT_WRITE',    'clients',   'Create and edit clients and move them through onboarding'),
    (gen_random_uuid(), 'CLIENT_APPROVE',  'clients',   'Activate clients (checker step)'),
    (gen_random_uuid(), 'CLIENT_PII_READ', 'clients',   'View unmasked PAN and bank account numbers'),
    (gen_random_uuid(), 'CLIENT_EXPORT',   'clients',   'Export client lists'),
    (gen_random_uuid(), 'DOCUMENT_READ',   'documents', 'View and download documents'),
    (gen_random_uuid(), 'DOCUMENT_UPLOAD', 'documents', 'Upload documents'),
    (gen_random_uuid(), 'DOCUMENT_REVIEW', 'documents', 'Approve, reject and archive documents'),
    (gen_random_uuid(), 'AUDIT_READ',      'audit',     'View audit logs');

INSERT INTO roles (id, code, name, description, system_role) VALUES
    (gen_random_uuid(), 'ADMIN',      'Administrator', 'Full access including user and role management', TRUE),
    (gen_random_uuid(), 'PARTNER',    'Partner / Reviewer', 'Reviews and approves work', TRUE),
    (gen_random_uuid(), 'ACCOUNTANT', 'Accountant', 'Prepares client work', TRUE),
    (gen_random_uuid(), 'VIEWER',     'Viewer', 'Read-only access', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN
    ('DASHBOARD_READ','USER_READ','ROLE_READ','CLIENT_READ','CLIENT_WRITE','CLIENT_APPROVE','CLIENT_PII_READ',
     'CLIENT_EXPORT','DOCUMENT_READ','DOCUMENT_UPLOAD','DOCUMENT_REVIEW','AUDIT_READ')
WHERE r.code = 'PARTNER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN
    ('DASHBOARD_READ','CLIENT_READ','CLIENT_WRITE','CLIENT_PII_READ','DOCUMENT_READ','DOCUMENT_UPLOAD','DOCUMENT_REVIEW')
WHERE r.code = 'ACCOUNTANT';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN
    ('DASHBOARD_READ','CLIENT_READ','DOCUMENT_READ')
WHERE r.code = 'VIEWER';
