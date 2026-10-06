package org.example.grab.domain.wish.dto;

import java.time.Instant;

/*
 * 내 WISH 목록 쿼리(네이티브)의 결과를 받는 프로젝션.
 * WISH 도메인이 DROP의 엔티티·repository를 직접 참조하지 않도록, 목록에 필요한 DROP 필드만
 * 같은 쿼리의 JOIN·서브쿼리로 계산해 담는다(CODING_CONVENTION 2.1).
 * 상태는 DB VARCHAR라 문자열로, TIMESTAMPTZ는 Instant로 받아 응답에서 OffsetDateTime으로 변환한다.
 */
public interface WishListProjection {

    Long getDropId();

    String getName();

    String getThumbnailUrl();

    Long getMinPrice();

    String getStatus();

    Instant getWishedAt();
}
