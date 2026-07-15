-- Account Service schema (H2). Owned here, not by Hibernate (ddl-auto: none).
-- See docs/implementation-plan.md §1.6.

CREATE TABLE IF NOT EXISTS accounts (
    account_id  VARCHAR(100)  PRIMARY KEY,
    balance     DECIMAL(19, 4) NOT NULL DEFAULT 0,
    currency    VARCHAR(3),
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS transactions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        VARCHAR(100)  NOT NULL UNIQUE,                       -- idempotency key from Gateway
    account_id      VARCHAR(100)  NOT NULL REFERENCES accounts(account_id), -- no orphaned transactions
    type            VARCHAR(10)   NOT NULL,
    amount          DECIMAL(19, 4) NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    event_timestamp TIMESTAMP     NOT NULL,
    applied_at      TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tx_account_ts ON transactions (account_id, event_timestamp);
