package org.example.grab.domain.drop.dto;

import java.time.Instant;

/*
 * 공개 DROP 목록 쿼리(네이티브)의 결과를 받는 프로젝션.
 * 상태는 DB VARCHAR라 문자열로 받고, 집계값은 Long·Boolean으로 받는다.
 * TIMESTAMPTZ는 Hibernate가 Instant로 돌려주므로 시각은 Instant로 받아 응답에서 OffsetDateTime으로 변환한다.
 */
public interface PublicDropListProjection {

    Long getDropId();

    String getName();

    String getStatus();

    Instant getSaleStartsAt();

    Instant getSaleEndsAt();

    Long getCategoryId();

    String getCategoryName();

    String getThumbnailUrl();

    Long getMinPrice();

    Boolean getSoldOut();

    Long getWishCount();
}
