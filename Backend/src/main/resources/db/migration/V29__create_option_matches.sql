-- 옵션별 재고 배분의 LLM 옵션 매칭 결과(GR-189, ERD.md 1.7, 수요 분석 API 7.4).
-- 같은 입력(프롬프트 버전·판매자 옵션 구성·후보 목록)이면 다시 부르지 않도록 입력 해시당 한 건만 둔다.
-- 저장 전 폼 입력에도 쓰므로 DROP·판매자와 연결하지 않고, 후보 글자·판매량 없이 고른 번호만 남긴다.
CREATE TABLE option_matches (
    input_hash VARCHAR(64) NOT NULL,
    matches JSONB NOT NULL,
    llm_model VARCHAR(100),
    prompt_version VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_option_matches PRIMARY KEY (input_hash)
);
