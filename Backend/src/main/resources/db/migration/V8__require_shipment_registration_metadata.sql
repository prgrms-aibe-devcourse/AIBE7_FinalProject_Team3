ALTER TABLE shipments
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN request_hash SET NOT NULL,
    ALTER COLUMN shipped_at SET NOT NULL;
