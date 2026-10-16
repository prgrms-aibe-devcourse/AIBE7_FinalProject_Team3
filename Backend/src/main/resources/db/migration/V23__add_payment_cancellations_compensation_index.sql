-- 보정 환불 배치(GR-88, ERD.md 3.3)는 결제당 보정 환불 기록을 하나만 만든다.
-- 두 인스턴스가 같은 결제의 기록을 함께 만들어도 하나만 저장되도록 보장하고,
-- 기록 생성 단계가 이미 기록이 있는 결제를 제외하는 조회(NOT EXISTS)에도 쓴다.
CREATE UNIQUE INDEX uq_payment_cancellations_compensation
    ON payment_cancellations (payment_id)
    WHERE purpose = 'LATE_APPROVAL_COMPENSATION';
