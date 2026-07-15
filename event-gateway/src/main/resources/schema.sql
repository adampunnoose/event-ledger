-- Event Gateway schema (H2). Owned here, not by Hibernate (ddl-auto: none).
-- See docs/implementation-plan.md §1.5.
--
-- Note: events.account_id is intentionally NOT a foreign key — the Gateway owns no
-- accounts table (account state lives across the service boundary in the Account Service).

CREATE TABLE IF NOT EXISTS events (
    event_id        VARCHAR(100)  PRIMARY KEY,       -- idempotency key
    account_id      VARCHAR(100)  NOT NULL,
    type            VARCHAR(10)   NOT NULL,           -- CREDIT | DEBIT
    amount          DECIMAL(19, 4) NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    event_timestamp TIMESTAMP     NOT NULL,
    metadata        CLOB,                             -- raw JSON string, opaque
    status          VARCHAR(20)   NOT NULL,           -- RECEIVED | APPLIED | FAILED
    received_at     TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_account_ts ON events (account_id, event_timestamp);
