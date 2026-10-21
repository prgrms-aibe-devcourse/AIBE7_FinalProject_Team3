# 주문·결제

- 경로: `/checkout?dropId=&optionId=&quantity=`(주문 생성 전), `/checkout/:orderId`(결제), 결제창 콜백 `/checkout/:orderId?result=success|fail` (모두 같은 화면의 상태)
- 요구사항: ORDER-001~003, STOCK-001~005, PAY-001~005
- [와이어프레임](../wireframes/CheckoutPage.html)

## 조회·처리 계약

상품 상세는 주문을 만들지 않고 고른 옵션·수량으로 `/checkout?dropId=&optionId=&quantity=`에 이동한다. 이 단계는 공개 상품 상세(`GET /api/v1/drops/{dropId}`)로 상품·옵션·예상 금액을 보여주고 배송지(수령인·연락처·우편번호·기본 주소, 선택: 상세 주소·배송 요청사항)를 입력받는다. `POST /api/v1/orders`에는 `Idempotency-Key`를 보내며 금액은 보내지 않는다. 생성에 성공하면 `/checkout/:orderId`로 교체 이동(replace)하고, 이후 화면은 `GET /api/v1/orders/{orderId}`의 `orderNumber`, `totalAmount`, `paymentExpiresAt`, 배송지를 기준으로 한다. 새로고침도 같은 조회로 복원한다.

결제창은 토스페이먼츠 SDK v2(`@tosspayments/tosspayments-sdk`)로 테스트 환경의 `CARD` 방식을 연다. 클라이언트 키는 `VITE_TOSS_CLIENT_KEY`, 고객 키는 비회원(`ANONYMOUS`), 토스 `orderId`는 `orderNumber`, 금액은 `totalAmount`다. `successUrl`은 `{origin}/#/checkout/{orderId}?result=success`, `failUrl`은 `?result=fail`이다. 토스는 결과 값(`paymentKey`, `orderId`, `amount` 또는 `code`, `message`)을 해시(`#`) 앞 쿼리에 붙여 돌아오므로 `window.location.search`에서 읽고, 읽은 즉시 주소에서 지운다(2026-10-20 테스트 결제로 확인). 성공 콜백의 `paymentKey`, `amount`로 `POST /api/v1/orders/{orderId}/payments`를 호출한다. PG의 `orderId`는 서버 주문 UUID가 아니라 `orderNumber`와 대조한다.

## 화면 구성

- PC: 배송지와 주문 상품을 왼쪽, 서버가 확정한 상품 금액·배송비·총액과 결제 버튼을 오른쪽에 둔다.
- 모바일: 배송지→상품→금액→결제 버튼 순서로 한 열에 배치한다.
- 주문 생성 전, 결제 대기, 결제 처리 중, 성공, 실패, 결과 확인 중을 같은 흐름에서 보여준다. 결과 화면에는 주문 상세로 가는 링크를 둔다.

## 행동과 이동

주문 생성은 한 번의 의도에 같은 멱등 키를 사용하고 중복 제출을 막는다. 같은 입력으로 다시 보내면 같은 키를, 입력을 바꾸면 새 키를 쓴다. 결제는 `PAYMENT_PENDING`이면서 마감 전일 때만 시작한다. 성공 콜백은 즉시 승인 API를 호출하고 같은 요청 재전송에는 같은 멱등 키·본문을 사용한다. 승인 멱등 키는 화면 메모리에만 보관한다. 콜백 주소를 새로고침해 새 키로 보낸 요청이 `409 PAYMENT_ALREADY_PROCESSED`를 받으면 주문·결제 이력을 다시 조회해 결과를 보여준다. 명확히 `FAILED`이고 주문이 아직 결제 대기·마감 전인 경우에만 결제창 재시도를 허용한다. 결과 불명(`PENDING`·`UNKNOWN`) 또는 `reconciliationStatus: REQUIRED`면 결제창을 다시 열지 않고 결과 확인 중으로 안내한다. 결제창을 닫으면(`USER_CANCEL`, 실패 콜백 없이 SDK 호출이 거부됨) 결제 대기 상태를 유지하고 다시 결제할 수 있다.

## 상태와 예외

재고 부족·판매 전/종료·주문 만료·결제 거절·콜백 금액/주문번호 불일치·네트워크 오류를 구분한다. 콜백의 `orderId`·`amount`가 주문의 `orderNumber`·`totalAmount`와 다르면 승인 API를 호출하지 않고 불일치로 안내한다. 결제 성공 표시는 `status: SUCCEEDED`와 `reconciliationStatus: NONE`, 주문 `PAID` 확인 후에만 한다. 콜백 실패에는 결제 상태를 확인하고 주문 상세로 돌아갈 수 있게 한다. 민감한 결제 키는 화면에 표시하지 않는다.

## 인수 조건

배송지 입력, 서버 확정 금액, 결제 마감, 테스트 결제 성공·거절·만료·결과 불명, 결제창 닫기, 새로고침·중복 클릭을 확인한다. e2e는 외부 요청을 막으므로 토스 SDK 스크립트를 콜백 주소로 이동만 하는 가짜 스크립트로 대체한다.

공통: 로딩·실패·권한 오류를 구분하고 키보드 포커스와 직접 진입·뒤로 가기를 확인한다. [공통 디자인 기준](../DESIGN.md)을 따르며 PC 1440×900, 모바일 390×844에서 가로 넘침이 없어야 한다. 실제 구현은 별도 이슈에서 수행한다.

## 디자인 적용 (2026-10-13)

배송·상품 패널과 결제 요약을 구분하고 총액을 크게 표시한다. PC 결제 요약은 상단에 고정되며 모바일에서는 기존 순서대로 표시한다.

[공통 디자인 기준](../DESIGN.md)을 함께 따른다.
