INSERT INTO users (id, email, password_hash, display_name, role, status, provider)
VALUES (1, 'seller1@example.com', 'hash', '판매자1', 'USER', 'ACTIVE', 'LOCAL');

INSERT INTO sellers (id, user_id, brand_name, contact_email, status)
VALUES (1, 1, '브랜드1', 'seller1@example.com', 'PENDING');

INSERT INTO categories (id, code, name, is_active)
VALUES (1, 'FASHION', '패션', TRUE);
