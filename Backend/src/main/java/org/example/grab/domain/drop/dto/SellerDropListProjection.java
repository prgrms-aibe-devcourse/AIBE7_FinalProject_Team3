package org.example.grab.domain.drop.dto;

import org.example.grab.domain.drop.entity.DropStatus;

import java.time.OffsetDateTime;

/*
 * 판매자 DROP 목록 쿼리의 결과를 받는 프로젝션.
 * 목록 쿼리의 상관 서브쿼리가 계산한 minPrice(활성 SKU 최저가, 없으면 null)를 그대로 담는다.
 */
public interface SellerDropListProjection {

    Long getDropId();

    String getName();

    DropStatus getStatus();

    Long getMinPrice();

    OffsetDateTime getCreatedAt();
}
