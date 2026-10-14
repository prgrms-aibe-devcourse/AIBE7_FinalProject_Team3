CREATE TABLE seller_documents (
    id                    UUID PRIMARY KEY,
    uploaded_by           BIGINT       NOT NULL REFERENCES users (id),
    seller_id             BIGINT REFERENCES sellers (id),
    document_type         VARCHAR(30)  NOT NULL,
    content_type          VARCHAR(100) NOT NULL,
    encrypted_object_key  VARCHAR(500) NOT NULL,
    encrypted_dek         VARCHAR(500) NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
