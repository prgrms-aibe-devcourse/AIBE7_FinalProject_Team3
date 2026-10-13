-- 상품 이미지를 갤러리(GALLERY)와 하단 상세 설명(DETAIL)으로 구분한다(GR-79, ERD.md drop_images).
-- 기존 행은 모두 갤러리 이미지였으므로 기본값으로 GALLERY를 채운다. URL·public_id·순서·대체 설명은 바꾸지 않는다.
-- 순서는 용도 안에서 0부터 매기므로 유일성 제약을 (drop_id, sort_order)에서 (drop_id, image_type, sort_order)로 바꾼다.

ALTER TABLE drop_images
    ADD COLUMN image_type VARCHAR(10) NOT NULL DEFAULT 'GALLERY';

ALTER TABLE drop_images
    ADD CONSTRAINT ck_drop_images_type CHECK (image_type IN ('GALLERY', 'DETAIL'));

ALTER TABLE drop_images
    DROP CONSTRAINT uq_drop_images_sort;

ALTER TABLE drop_images
    ADD CONSTRAINT uq_drop_images_type_sort UNIQUE (drop_id, image_type, sort_order);
