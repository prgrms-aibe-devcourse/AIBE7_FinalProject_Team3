ALTER TABLE payments
    ADD COLUMN request_hash VARCHAR(64) NOT NULL;

ALTER TABLE payment_cancellations
    ADD COLUMN request_hash VARCHAR(64) NOT NULL;

ALTER TABLE shipments
    ADD COLUMN idempotency_key VARCHAR(100) NOT NULL,
    ADD COLUMN request_hash VARCHAR(64) NOT NULL;

ALTER TABLE shipments
    ADD CONSTRAINT uq_shipments_idempotency UNIQUE (idempotency_key);
