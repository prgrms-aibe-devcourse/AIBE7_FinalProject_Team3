// 결제 대기 만료 + 취소 동시 발생 시나리오 3/3.
// 02-backdate.sql 실행 직후 바로 실행해, 백그라운드에서 10초마다 도는 만료 배치
// (PaymentBatchScheduler)와 취소 요청이 같은 주문을 두고 경합하게 만든다.
// 둘 중 누가 먼저 처리하든 재고는 한 번만 반환돼야 한다 — 결과는 Backend/k6/03-expiry/verify.sql로 확인.
//
//   k6 run -e ORDER_ID=<UUID> Backend/k6/03-expiry/03-cancel-order.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

export const options = { vus: 1, iterations: 1 };

export function setup() {
    const orderId = __ENV.ORDER_ID;
    if (!orderId) {
        throw new Error('ORDER_ID 환경변수가 필요합니다. 01-create-order.js 출력을 확인하세요.');
    }
    return { session: login('k6-buyer@loadtest.local', 'LoadTest1234!'), orderId };
}

export default function (data) {
    primeCookies(data.session);

    const res = http.post(
        `${BASE_URL}/api/v1/orders/${data.orderId}/cancel`,
        JSON.stringify({ reason: 'k6 만료/취소 동시성 테스트' }),
        { headers: authHeaders(data.session, { 'Idempotency-Key': uuidv4() }) }
    );

    // 취소가 이기면 200, 배치가 먼저 만료시켰으면 cancelable()이 false가 돼 409 ORDER_NOT_CANCELABLE이
    // 난다(OrderCancelTransactionService.prepare). 매번 새 Idempotency-Key를 쓰므로 그 외의
    // 409(DUPLICATE_IDEMPOTENCY_KEY, ORDER_STATUS_CONFLICT)는 이 시나리오에서 나오면 안 된다.
    check(res, {
        '200(취소 성공) 또는 409(이미 배치가 처리함)': (r) => {
            if (r.status === 200) return true;
            if (r.status !== 409) return false;
            return JSON.parse(r.body).error.code === 'ORDER_NOT_CANCELABLE';
        },
    });
}

export function teardown() {
    console.log('완료: Backend/k6/03-expiry/verify.sql로 해당 주문의 재고가 정확히 한 번만 반환됐는지 확인하세요.');
}
