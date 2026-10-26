# PG 장애 주입 (payment-fault-injection, GR-155)

> 공통 실행 환경·전제는 `../README.md` 참고. MockPaymentGateway 전제는 스크립트 상단 주석 참고.

## 2026-10-26: 장애 주입 방법 결정 및 3분 검증 통과

**장애 주입 방법**: 토스 샌드박스 자체 기능 대신 `MockPaymentGateway`(GR-154에서
추가)에 장애 주입 설정(`grab.payment.mock-gateway.confirm-failure-rate`,
`confirm-unknown-rate`, `cancel-unknown-rate`, `resolve-delay-seconds`)을
추가해 애플리케이션 레벨에서 재현한다. 샌드박스는 반복 자동화 테스트에 맞지
않고(GR-149 전제), PG 레이어를 직접 흉내 내면 실패율·타이밍을 정밀하게
제어하고 재현할 수 있다.

**핵심 설계 — 즉시 해소되지 않는 결과불명**: 단순히 confirm()/cancel()에서 매번
새로 확률을 굴려 UNKNOWN을 돌려주면, `PaymentService`가 confirm 직후 한 번
호출하는 즉시 조회(`lookup()`)에서 바로 풀려버려 배치가 개입할 일이 없다.
그래서 UNKNOWN으로 떨어진 건을 `resolveDelaySeconds`(기본 20초)가 지나야
확정되게 보류 상태로 저장해, `PaymentBatchService`의 "진행 중 결제 정리"·
"결제 취소 재요청"이 나중에 다시 물어봐서 해소하는 경로를 실제로 타게 했다.

**3분 스모크 테스트** (`CONFIRM_FAILURE_RATE=0.1`, `CONFIRM_UNKNOWN_RATE=0.1`,
`CANCEL_UNKNOWN_RATE=0.3`, VU 10): HTTP 레벨 체크 1652/1652(100%) 성공,
threshold `fault_http_failures_total count==0` 통과. 설정한 비율과 실측
비율이 거의 일치(승인 거절 ~10.7%, 승인 결과불명 ~8.4%, 취소 결과불명
~31.7%).

**테스트 직후 verify.sql**: 581건 중 14건이 아직 `UNKNOWN`(방금 생성돼
`resolveDelaySeconds`가 아직 안 지난 것들) — `stuck_in_progress_payments`는
0이라 밀린 건 아니었다. **약 90초 뒤 재확인하니 UNKNOWN 0건으로 전부 해소**:

- 재고 정합성 위반 0행
- 최종 결제 상태: `CANCELED` 470, `FAILED` 62, `SUCCEEDED` 49 — `UNKNOWN` 남은 것 없음
- `stuck_in_progress_payments` 0건, `stuck_in_progress_cancellations`(생성 후 2분 지난 것) 0건
- 결제당 보정 환불 기록 중복 0건(`uq_payment_cancellations_compensation` 제약과 일치)
- `unresolved_reconciliation` 0건

이 3분 스모크 당시엔 `verify.sql`에 생성 시각과 무관한 전체 미해결 취소 집계
(`unresolved_cancellations_total`)가 없어, 위 수치는 "오래(2분 이상) 방치된 취소는
없었다"는 것까지만 보여준다. 결제 쪽은 `UNKNOWN` 남은 것 없음을 상태 분포로 직접
확인했지만, 취소 쪽은 같은 수준의 전수 확인을 이 실행에서는 하지 못했다 — 재실행
시 `unresolved_cancellations_total`도 함께 확인해야 한다.

## 결론

MockPaymentGateway의 장애 주입이 의도대로 동작하고(비율·지연 모두 설정값과
일치), `PaymentBatchService`의 진행 중 결제 정리·취소 재요청 배치가 주입된
결제 결과불명을 전부 해소했다(상태 분포로 확인). 취소 결과불명은 오래 방치된
건이 없음을 확인했고(전수 확인은 다음 실행에서 보강 필요), 보정 환불 기록
중복 생성도 없었고 재고 정합성도 깨지지 않았다.

**완료 조건 대비 현황**:
- [x] PG 장애를 로컬에서 재현하는 방법이 정해지고 문서화된다(MockPaymentGateway
  장애 주입 설정, 이 스크립트 상단 주석)
- [x] 장애 주입 시나리오가 레포에 존재한다
- [x] 결과(취소 재요청 지연·보정 누락·중복 여부)가 기록된다 — 지연 0건, 누락 0건,
  중복 0건
- [x] 문제가 발견되면 별도 버그 이슈로 뗀다 — 이번 3분 스모크에서는 문제 없음

**범위 밖(미검증)**: 3분·VU 10 수준의 스모크만 했다. 동시에 훨씬 많은 건이
결과불명으로 몰리는 상황(예: PG 자체 장애로 대량 동시 타임아웃)에서도 배치가
밀리지 않는지는 더 긴 시간·더 높은 VU로 별도 검증이 필요하다.
