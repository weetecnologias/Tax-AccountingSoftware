# TAXPILOT AI — Database (Phase 1)

PostgreSQL 17 · Flyway · UUID primary keys · `timestamptz` everywhere · all client-owned tables carry `organisation_id`.

Phase 1 creates only the tables Phase 1 uses. Later phases add their tables in new migrations
(see the full table list in the master specification, Part 20).

```mermaid
erDiagram
    organisations ||--o{ users : employs
    organisations ||--o{ clients : services
    users }o--o{ roles : "user_roles"
    roles }o--o{ permissions : "role_permissions"
    users ||--o{ user_sessions : has
    users ||--o{ login_history : has
    clients ||--o{ client_contacts : has
    clients ||--o{ client_addresses : has
    clients ||--o{ gst_registrations : has
    clients ||--o{ bank_accounts : has
    clients ||--o{ client_status_history : has
    clients ||--o{ documents : owns
    users ||--o{ clients : "assigned accountant / reviewer"
    users ||--o{ documents : uploads
    users ||--o{ audit_logs : performs

    organisations { uuid id PK; text name; text pan; timestamptz created_at }
    users { uuid id PK; uuid organisation_id FK; citext email UK; text full_name; text password_hash; bool active; bool must_change_password; int failed_login_count; timestamptz locked_until; timestamptz last_login_at }
    roles { uuid id PK; text code UK; text name; bool system_role }
    permissions { uuid id PK; text code UK; text description; text module }
    user_sessions { uuid id PK; uuid user_id FK; text refresh_token_hash UK; timestamptz expires_at; timestamptz revoked_at; inet ip_address }
    login_history { uuid id PK; uuid user_id FK; citext email; bool success; text failure_reason; inet ip_address; timestamptz occurred_at }
    clients { uuid id PK; uuid organisation_id FK; text client_code UK; text display_name; text legal_name; text entity_type; bytea pan_encrypted; text pan_hash UK; text pan_last4; text tan; text status; int fy_start_month; text accounting_method; uuid accountant_id FK; uuid reviewer_id FK; text closure_reason; bigint version }
    client_contacts { uuid id PK; uuid client_id FK; text name; text designation; text email; text phone; bool is_primary }
    client_addresses { uuid id PK; uuid client_id FK; text address_type; text line1; text city; text state_code; text pincode }
    gst_registrations { uuid id PK; uuid client_id FK; text gstin UK; text state_code; text registration_type; date effective_date; text filing_frequency; text status }
    bank_accounts { uuid id PK; uuid client_id FK; text bank_name; bytea account_number_encrypted; text account_number_last4; text ifsc; text account_type; bool active }
    client_status_history { uuid id PK; uuid client_id FK; text from_status; text to_status; uuid changed_by FK; text comment; timestamptz changed_at }
    documents { uuid id PK; uuid client_id FK; text document_type; text original_filename; text content_type; bigint size_bytes; text sha256; text storage_key; text status; text scan_status; text financial_year; uuid uploaded_by FK; uuid reviewed_by FK; text review_comment }
    audit_logs { uuid id PK; timestamptz occurred_at; uuid actor_user_id; text actor_email; text action; text entity_type; uuid entity_id; uuid client_id; jsonb details; inet ip_address; text request_id }
```

## Phase 2A — accounting (V6, V7)

```mermaid
erDiagram
    clients ||--o{ ledger_accounts : "chart of accounts"
    ledger_accounts ||--o{ ledger_accounts : "parent group"
    clients ||--o{ accounting_periods : "monthly periods"
    clients ||--o{ journal_entries : books
    accounting_periods ||--o{ journal_entries : contains
    journal_entries ||--|{ journal_entry_lines : has
    ledger_accounts ||--o{ journal_entry_lines : "posted to"
    journal_entries |o--o| journal_entries : "reversal of"
    clients ||--o{ journal_sequences : numbering

    ledger_accounts { uuid id PK; uuid client_id FK; text code UK; text name; text account_type; text subtype; uuid parent_id FK; bool is_group; bool active; text template_code }
    accounting_periods { uuid id PK; uuid client_id FK; text financial_year; smallint period_number; date start_date; date end_date; text status }
    journal_entries { uuid id PK; uuid client_id FK; text entry_number UK; date entry_date; uuid period_id FK; text entry_type; text status; numeric total_debit; numeric total_credit; uuid prepared_by; uuid approved_by; uuid posted_by; uuid reversal_of_id; uuid reversed_by_id; text idempotency_key UK }
    journal_entry_lines { uuid id PK; uuid journal_entry_id FK; smallint line_no; uuid account_id FK; numeric debit; numeric credit }
    journal_sequences { uuid client_id PK; text financial_year PK; int next_value }
```

Accounting integrity (database triggers, in addition to application checks):
- A line has exactly one non-zero side; amounts are `NUMERIC(19,2)` and never negative.
- Posting requires ≥ 2 lines, total debit = total credit > 0, totals matching the lines, only active non-group
  accounts of the same client, and an OPEN period containing the entry date.
- Posted entries cannot be deleted or changed (only `reversed_by_id` may be set once); their lines are frozen.
- Voucher numbers are allocated per client and financial year inside the posting transaction (no gaps).

## Integrity rules

- `audit_logs` is **append-only**: a trigger rejects `UPDATE` and `DELETE`.
- `clients.pan_hash` is unique per organisation (HMAC blind index of the normalised PAN).
- `gst_registrations.gstin` is globally unique; the PAN inside the GSTIN must match the client's PAN (service rule).
- `documents (client_id, sha256)` is indexed; a repeat upload is accepted but flagged `DUPLICATE_SUSPECTED`.
- Clients are never hard-deleted. Closing a client sets `status = CLOSED`, keeps all records, and revokes assignments.
- Optimistic locking (`version`) on `clients` and `documents` prevents lost updates.
