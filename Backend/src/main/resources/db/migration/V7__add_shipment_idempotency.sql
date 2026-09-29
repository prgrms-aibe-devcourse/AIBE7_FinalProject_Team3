ALTER TABLE shipments
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN request_hash CHAR(64);
