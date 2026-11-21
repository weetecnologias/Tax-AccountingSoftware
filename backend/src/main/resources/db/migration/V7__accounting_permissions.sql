INSERT INTO permissions (id, code, module, description) VALUES
    (gen_random_uuid(), 'ACCOUNTING_READ',    'accounting', 'View chart of accounts, journals, ledgers and financial statements'),
    (gen_random_uuid(), 'ACCOUNTING_WRITE',   'accounting', 'Maintain chart of accounts, open financial years, prepare and submit journals'),
    (gen_random_uuid(), 'ACCOUNTING_APPROVE', 'accounting', 'Approve and post journals, reverse posted journals, close and reopen periods');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('ACCOUNTING_READ','ACCOUNTING_WRITE','ACCOUNTING_APPROVE')
WHERE r.code IN ('ADMIN','PARTNER');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN ('ACCOUNTING_READ','ACCOUNTING_WRITE')
WHERE r.code = 'ACCOUNTANT';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'ACCOUNTING_READ'
WHERE r.code = 'VIEWER';
