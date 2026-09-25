# GRAB API 공통 규칙

## 1. 공통 규칙

### 1.1 기본 정보

```yaml
Base URL: /api/v1
Content-Type: application/json
인증 방식: HttpOnly Access Token 쿠키
시간 형식: ISO 8601 (예: 2026-09-18T14:00:00+09:00)
금액 단위: KRW, 정수
페이지 번호: 0부터 시작
```

### 1.2 인증 쿠키

```http
Set-Cookie: access_token=<jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=<access-ttl>
Set-Cookie: refresh_token=<token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=<refresh-ttl>
```

Access Token과 Refresh Token은 응답 본문 및 브라우저 저장소에 노출하지 않는다. 브라우저 요청은 쿠키를 포함하도록 설정한다.

### 1.3 CSRF 보호

`POST`, `PUT`, `PATCH`, `DELETE` 요청은 CSRF 토큰을 헤더로 전달한다. 프론트엔드는 인증 요청 전에 `GET /api/v1/auth/csrf`를 호출해 `XSRF-TOKEN` 쿠키를 발급받고 같은 값을 헤더에 넣는다. 이 쿠키는 인증 토큰이 아니므로 JavaScript에서 읽을 수 있다.

```http
Cookie: XSRF-TOKEN=<csrf-token>
X-XSRF-TOKEN: <csrf-token>
```

CORS는 허용된 프론트엔드 Origin만 등록하고 Credential 요청을 허용한다.

### 1.4 공통 성공 응답

```json
{
  "success": true,
  "data": {},
  "message": null
}
```

### 1.5 공통 오류 응답

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "오류 메시지",
    "fieldErrors": [
      {
        "field": "email",
        "reason": "올바른 이메일 형식이 아닙니다."
      }
    ]
  }
}
```

> `fieldErrors`는 요청 본문 검증 실패뿐 아니라, 도메인 검증에서 위반 항목을 식별해야 할 때도 채워집니다. 해당 사항이 없으면 빈 배열입니다.

### 1.6 공통 HTTP 상태 코드

| 상태 코드 | 의미 |
| --- | --- |
| 200 | 요청 성공 |
| 201 | 리소스 생성 성공 |
| 204 | 요청 성공, 응답 본문 없음 |
| 400 | 잘못된 요청 또는 유효성 검증 실패 |
| 401 | 인증 필요 또는 토큰 오류 |
| 403 | 권한 또는 소유권 없음 |
| 404 | 리소스를 찾을 수 없음 |
| 409 | 중복 요청 또는 상태 충돌 |
| 422 | 비즈니스 규칙 위반 |
| 429 | 요청 횟수 제한 초과 |
| 500 | 서버 내부 오류 |

### 1.7 페이지 응답

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 100,
  "totalPages": 5,
  "hasNext": true
}
```

### 1.8 리소스 식별자

외부에 노출하는 리소스는 DB의 자동 증가 PK 대신 UUID 형식의 공개 식별자(`public_id`)를 사용합니다.
경로 변수와 응답 필드 모두 이 값을 문자열로 주고받습니다.

| 리소스 | 노출 식별자 | 대응 컬럼 |
| --- | --- | --- |
| 회원 | `userId` | `users.public_id` |
| 판매자·판매자 신청 | `sellerId`, `applicationId` | `sellers.public_id` |
| DROP 이미지 | `imageId` | `drop_images.public_id` |
| 주문 | `orderId` | `orders.public_id` |
| 결제 | `paymentId` | `payments.public_id` |
| 결제 취소 | `cancellationId` | `payment_cancellations.public_id` |

DROP과 옵션(`dropId`, `optionId`, `groupId`, `valueId`, `categoryId`)은 공개 카탈로그 데이터이므로 정수 ID를 그대로 사용합니다.
UUID 형식이 아닌 값이 경로 변수로 들어오면 `RESOURCE_NOT_FOUND`(404)로 응답합니다.

---

## 2. 주요 상태 정의

### 2.1 회원 역할

| 역할 | 설명 |
| --- | --- |
| `USER` | 모든 활성 회원에게 부여되는 기본 권한 |
| `SELLER` | `sellers.status = APPROVED`일 때 파생되는 판매자 권한 |
| `ADMIN` | `users.role = ADMIN`인 운영자 권한 |

> API의 `roles`는 유효 권한 목록입니다. `SELLER`는 `users.role`에 중복 저장하지 않고 승인된 판매자 상태에서 계산합니다.

### 2.2 판매자 신청 상태

| 상태 | 설명 |
| --- | --- |
| `PENDING` | 승인 대기 |
| `APPROVED` | 승인 |
| `REJECTED` | 반려 |

### 2.3 DROP 상태

| 상태 | 설명 |
| --- | --- |
| `DRAFT` | 임시 저장 |
| `WISH` | 공개 및 WISH 등록 가능 |
| `GRAB` | 판매 진행 중 |
| `ENDED` | 판매 종료 |
| `CANCELED` | 출시 취소 |

> 품절 여부는 DROP 상태와 별개인 `soldOut` 값으로 표현합니다.

### 2.4 주문 상태

| 상태 | 설명 |
| --- | --- |
| `PAYMENT_PENDING` | 결제 대기 |
| `PAID` | 결제 완료 |
| `PREPARING` | 배송 준비 |
| `SHIPPED` | 배송 중 |
| `DELIVERED` | 배송 완료 |
| `CANCELED` | 취소 완료 |
| `EXPIRED` | 결제 기한 만료 |

> 개별 결제 시도의 실패는 결제 상태로 기록하며 주문은 결제 마감 전까지 `PAYMENT_PENDING`을 유지할 수 있습니다.

### 2.5 결제 상태

| 상태 | 설명 |
| --- | --- |
| `PENDING` | 결제 시도 중 |
| `SUCCEEDED` | 결제 성공 |
| `FAILED` | 결제 실패 |
| `UNKNOWN` | 외부 PG 결과 확인 필요 |
| `CANCELED` | 결제 취소 완료 |

> 보정 필요 여부는 결제 상태와 분리된 `reconciliationStatus`(`NONE`, `REQUIRED`, `RESOLVED`)로 표현합니다.

---

## 3. 주요 오류 코드

| 오류 코드 | HTTP 상태 | 설명 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | 요청 형식 오류 |
| `VALIDATION_FAILED` | 400 | 필드 검증 실패 |
| `INVALID_EMAIL` | 400 | 이메일 형식·길이 규칙 위반 |
| `INVALID_PASSWORD` | 400 | 비밀번호 길이·공백·허용 문자·문자 조합 규칙 위반 |
| `INVALID_NICKNAME` | 400 | 닉네임 길이·허용 문자 규칙 위반 |
| `AUTHENTICATION_REQUIRED` | 401 | 인증 필요 |
| `INVALID_TOKEN` | 401 | 유효하지 않은 토큰 |
| `ACCESS_DENIED` | 403 | 권한 없음 |
| `RESOURCE_NOT_FOUND` | 404 | 리소스 없음 |
| `DUPLICATE_EMAIL` | 409 | 이메일 중복 |
| `DUPLICATE_NICKNAME` | 409 | 닉네임 중복 (대소문자 무시) |
| `INVALID_STATE_TRANSITION` | 409 | 허용되지 않은 상태 전이 |
| `DROP_NOT_EDITABLE` | 409 | 수정할 수 없는 DROP |
| `DROP_NOT_WISHABLE` | 409 | WISH 불가능 상태 |
| `DROP_NOT_ON_SALE` | 409 | 판매 상태가 아님 |
| `DUPLICATE_OPTION_COMBINATION` | 409 | 동일한 옵션값 조합의 SKU 중복 |
| `ORDER_NOT_CANCELABLE` | 409 | 취소할 수 없는 주문 |
| `ORDER_STATUS_CONFLICT` | 409 | 주문 상태 동시 변경 충돌 |
| `PAYMENT_ALREADY_PROCESSED` | 409 | 이미 처리된 결제 |
| `INSUFFICIENT_STOCK` | 422 | 재고 부족 |
| `INVALID_OPTION_COMBINATION` | 422 | 옵션 그룹·값·SKU 조합 검증 실패 |
| `PAYMENT_AMOUNT_MISMATCH` | 422 | 결제 금액 불일치 |
| `PAYMENT_EXPIRED` | 422 | 결제 유효시간 만료 |
| `INVALID_SCHEDULE` | 422 | 판매 일정 오류 |

---

## 4. 백그라운드 처리 규칙

1. 서버 시각이 `saleStartsAt`에 도달하면 `WISH` → `GRAB`으로 전환한다.
2. 서버 시각이 `saleEndsAt`에 도달하면 `GRAB` → `ENDED`로 전환한다.
3. 결제 대기 시간이 만료되면 주문을 `EXPIRED`로 전환한다.
4. 만료된 주문의 확보 재고를 한 번만 반환한다.
5. 모든 옵션의 `availableStock`이 0이면 `soldOut=true`로 처리한다.
6. 결제 성공 웹훅이 만료 후 도착하면 자동 완료하지 않고 보정 대상으로 기록한다.

---

## 5. 멱등성 적용 대상

다음 요청은 `Idempotency-Key`를 지원합니다.

- `POST /api/v1/orders`
- `POST /api/v1/orders/{orderId}/payments`
- `POST /api/v1/orders/{orderId}/cancel`
- `POST /api/v1/seller/orders/{orderId}/shipment`

> 동일 키와 동일 요청 본문이 다시 전달되면 최초 처리 결과를 반환합니다. 동일 키에 서로 다른 요청 본문이 전달되면 `409 Conflict`를 반환합니다.
>
> Mock PG 웹훅은 `Idempotency-Key` 헤더 대신 PG가 전달한 `eventId`를 고유 키로 사용합니다.
>
> 서버가 PG로 나가는 결제·결제 취소 요청에 붙이는 멱등키(`payments.idempotency_key`, `payment_cancellations.idempotency_key`)는
> 클라이언트 헤더 값을 재사용하지 않고 서버가 UUID로 새로 생성해 저장합니다.

---

## 6. 권한 표기 기준

| 표기 | 설명 |
| --- | --- |
| 불필요 | 비회원도 호출 가능 |
| USER | 로그인한 일반 회원 이상 |
| SELLER | 승인된 판매자 권한 필요 |
| ADMIN | 운영자 권한 필요 |
| USER/SELLER | 주문 소유자 또는 해당 DROP의 판매자 |
| PG 검증 | 사용자 토큰 대신 Webhook 서명 또는 별도 Secret 검증 |
