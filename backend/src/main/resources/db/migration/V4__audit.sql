-- Append-only audit log.

CREATE TABLE audit_logs (
    id             UUID PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_user_id  UUID,
    actor_email    VARCHAR(254),
    action         VARCHAR(60)  NOT NULL,
    entity_type    VARCHAR(60)  NOT NULL,
    entity_id      UUID,
    client_id      UUID,
    details        JSONB,
    ip_address     VARCHAR(45),
    request_id     VARCHAR(64)
);
CREATE INDEX ix_audit_occurred ON audit_logs (occurred_at DESC);
CREATE INDEX ix_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_client ON audit_logs (client_id, occurred_at DESC);
CREATE INDEX ix_audit_actor ON audit_logs (actor_user_id, occurred_at DESC);

CREATE FUNCTION audit_logs_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_no_update
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_immutable();

CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION audit_logs_immutable();
