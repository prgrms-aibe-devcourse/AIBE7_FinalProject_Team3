-- 판매자 취소 시 사유를 남긴다(GR-18, DROP.md). 취소는 WISH && 판매 시작 전에만 가능하다.
-- 다른 종료 사유(TIME_EXPIRED·SOLD_OUT)에는 사유가 없으므로 NULL 허용으로 둔다.
-- 요청 사유는 @NotBlank @Size(max = 500)으로 검증하되, 로그에는 원문을 남기지 않는다.

ALTER TABLE drops
    ADD COLUMN cancel_reason VARCHAR(500);
