package org.example.grab.domain.demand.dto;

// 표본을 고른 방법(DEMAND.md 1.2·5.1). SIMILAR는 임베딩 유사도(GR-147), CATEGORY는 같은 카테고리 최신순이다
public enum SampleSelection {
    SIMILAR,
    CATEGORY
}
