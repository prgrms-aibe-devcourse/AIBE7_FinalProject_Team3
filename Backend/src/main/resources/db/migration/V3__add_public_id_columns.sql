-- 외부에 노출하는 리소스에 열거 불가능한 공개 식별자를 추가한다.
-- PK(BIGINT IDENTITY)는 내부 조인·정렬·행 잠금용으로 그대로 유지하고,
-- API 경로와 응답, 외부 연동에는 public_id만 사용한다.
--
-- gen_random_uuid()는 PostgreSQL 13+ 내장 함수이며 Java의 UUID.randomUUID()와
-- 같은 v4를 생성하므로, 애플리케이션이 직접 채워도 값 형식이 섞이지 않는다.
-- DEFAULT는 기존 행 백필과 누락 방지용 안전망으로 남겨 둔다.

ALTER TABLE users
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_users_public_id UNIQUE (public_id);

ALTER TABLE sellers
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_sellers_public_id UNIQUE (public_id);

-- 이미지 객체 키(presigned URL 발급 시 `images/{public_id}.{ext}`)로 사용한다.
ALTER TABLE drop_images
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_drop_images_public_id UNIQUE (public_id);

ALTER TABLE orders
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_orders_public_id UNIQUE (public_id);

ALTER TABLE payments
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_payments_public_id UNIQUE (public_id);

ALTER TABLE payment_cancellations
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_payment_cancellations_public_id UNIQUE (public_id);
