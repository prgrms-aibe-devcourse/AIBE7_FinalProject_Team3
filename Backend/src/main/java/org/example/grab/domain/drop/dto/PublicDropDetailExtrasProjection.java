package org.example.grab.domain.drop.dto;

/*
 * 공개 DROP 상세에 붙는 다른 테이블 값(브랜드·카테고리·활성 WISH 수)을 한 번에 받는 프로젝션(GR-184).
 * 세 값을 판매자·카테고리·WISH 도메인 service로 따로 조회하면 요청마다 DB 왕복이 세 번 생겨서 네이티브 쿼리 하나로 모았다.
 */
public interface PublicDropDetailExtrasProjection {

    String getBrandName();

    Long getCategoryId();

    String getCategoryName();

    Long getWishCount();
}
