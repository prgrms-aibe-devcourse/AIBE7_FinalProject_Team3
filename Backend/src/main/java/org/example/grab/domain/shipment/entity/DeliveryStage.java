package org.example.grab.domain.shipment.entity;

// 실제 택배사 연동이 없는 부트캠프 프로젝트라 Mock으로 보여주는 배송 세부 단계다(GR-86).
public enum DeliveryStage {
    COLLECTED,
    LINE_HAUL_OUT,
    LINE_HAUL_IN,
    OUT_FOR_DELIVERY
}
