-- 사업자등록번호(SELLER-001/SELLER-005). 신청 API는 항상 실제 값을 넣는다.
-- 기존 테스트 픽스처 등 이 컬럼을 모르는 INSERT가 많아(25곳), NOT NULL을 깨지 않도록
-- 생략 시 시퀀스로 고유 더미 값을 채우는 기본값을 둔다.
-- ponytail: 더미 값이 실제 사업자등록번호와 우연히 겹칠 가능성은 사실상 없다.
--   문제가 되면 그때 기존 INSERT들에 실제 값을 채우고 기본값을 제거한다.
CREATE SEQUENCE seq_sellers_business_registration_number_fallback;

ALTER TABLE sellers
    ADD COLUMN business_registration_number VARCHAR(10) NOT NULL
        DEFAULT lpad(nextval('seq_sellers_business_registration_number_fallback')::text, 10, '0');

ALTER TABLE sellers
    ADD CONSTRAINT uq_sellers_business_registration_number UNIQUE (business_registration_number);
