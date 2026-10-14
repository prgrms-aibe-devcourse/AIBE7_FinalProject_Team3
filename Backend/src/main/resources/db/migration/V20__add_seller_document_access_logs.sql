CREATE TABLE seller_document_access_logs (
    id             BIGSERIAL PRIMARY KEY,
    admin_id       BIGINT      NOT NULL REFERENCES users (id),
    seller_id      BIGINT      NOT NULL REFERENCES sellers (id),
    document_type  VARCHAR(30) NOT NULL,
    accessed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_seller_document_access_logs_seller ON seller_document_access_logs (seller_id, accessed_at);
