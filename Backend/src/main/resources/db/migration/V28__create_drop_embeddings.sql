-- DROP 임베딩(GR-147, ERD.md 1.7, 수요 분석 API 6절). 비슷한 상품 표본을 고르는 검색에만 쓴다.
-- 공개 후에는 상품 정보를 바꿀 수 없어 DROP당 한 건만 두고, 모델을 바꾸면 같은 행을 덮어쓴다.
-- pgvector 확장이 필요하다. 테스트·로컬·CI는 pgvector/pgvector 이미지, 운영 Supabase는 기본 제공 확장을 쓴다.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE drop_embeddings (
    drop_id BIGINT NOT NULL,
    embedding VECTOR(768) NOT NULL,
    model VARCHAR(100) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_drop_embeddings PRIMARY KEY (drop_id),
    CONSTRAINT fk_drop_embeddings_drop FOREIGN KEY (drop_id) REFERENCES drops (id)
);

-- 유사 상품 표본 검색(코사인 거리)
CREATE INDEX idx_drop_embeddings_embedding ON drop_embeddings USING hnsw (embedding vector_cosine_ops);
