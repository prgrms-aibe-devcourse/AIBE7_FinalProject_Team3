package org.example.grab.domain.wish.dto;

/*
 * 판매 시작 메일 수신자 쿼리(네이티브)의 결과를 받는 프로젝션(GR-69).
 * WISH 도메인이 DROP·User의 엔티티·repository를 직접 참조하지 않도록, 메일 한 통을 만드는 데 필요한
 * 값(받는 주소·DROP 이름)만 같은 쿼리의 JOIN으로 담는다(CODING_CONVENTION 2.1).
 */
public interface WishMailRecipientProjection {

    Long getDropId();

    String getDropName();

    String getEmail();
}
