-- Identity, organisation, users, roles, permissions, sessions.

CREATE TABLE organisations (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id                    UUID PRIMARY KEY,
    organisation_id       UUID         NOT NULL REFERENCES organisations (id),
    email                 VARCHAR(254) NOT NULL,
    full_name             VARCHAR(200) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password  BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_count    INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE permissions (
    id           UUID PRIMARY KEY,
    code         VARCHAR(60)  NOT NULL UNIQUE,
    module       VARCHAR(60)  NOT NULL,
    description  VARCHAR(300) NOT NULL
);

CREATE TABLE roles (
    id           UUID PRIMARY KEY,
    code         VARCHAR(60)  NOT NULL UNIQUE,
    name         VARCHAR(120) NOT NULL,
    description  VARCHAR(300),
    system_role  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE role_permissions (
    role_id        UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id  UUID NOT NULL REFERENCES permissions (id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id  UUID NOT NULL REFERENCES roles (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE user_sessions (
    id                  UUID PRIMARY KEY,
    user_id             UUID         NOT NULL REFERENCES users (id),
    refresh_token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,
    revoked_at          TIMESTAMPTZ,
    ip_address          VARCHAR(45),
    user_agent          VARCHAR(300)
);
CREATE INDEX ix_user_sessions_user ON user_sessions (user_id);

CREATE TABLE login_history (
    id              UUID PRIMARY KEY,
    user_id         UUID REFERENCES users (id),
    email           VARCHAR(254) NOT NULL,
    success         BOOLEAN      NOT NULL,
    failure_reason  VARCHAR(60),
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(300),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_history_user ON login_history (user_id, occurred_at DESC);
