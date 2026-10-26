// PERF-xxx(GR-155): 다수 주문이 동시에 결제를 시도하는 와중에 PG 응답이 거절·결과불명으로
// 떨어지는 상황을 재현해, PaymentBatchService의 "진행 중 결제 정리"(결과불명 승인 재조회)와
// "결제 취소 재요청"(결과불명 취소 재조회)이 배치 주기 안에서 밀리지 않고 처리되는지,
// PaymentCancellation·보정 환불 기록이 중복 생성되지 않는지 본다.
//
// 장애는 실제 토스 샌드박스가 아니라 MockPaymentGateway의 장애 주입 설정으로 만든다
// (GR-149 전제: 외부 PG에 반복 호출을 보내면 레이트리밋·키 정지로 이어질 수 있어서다).
// 백엔드를 이 설정으로 띄운 상태에서 돌린다:
//   GRAB_PAYMENT_MOCK_GATEWAY_ENABLED=true GRAB_MOCK_CONFIRM_FAILURE_RATE=0.1 \
//   GRAB_MOCK_CONFIRM_UNKNOWN_RATE=0.1 GRAB_MOCK_CANCEL_UNKNOWN_RATE=0.3 \
//   docker compose -f compose.yaml -f compose.loadtest-micro.yaml up --build
//
// 사전 준비: common/accounts-seed.sql, payment-fault-injection/seed.sql 실행 후
// 출력된 drop_id/option_id를 -e로 넘긴다.
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 k6/payment-fault-injection/payment-fault-injection.js
//
// MockPaymentGateway의 resolveDelaySeconds(기본 20초)보다 충분히 길게 돌려야 결과불명이
// 배치로 해소되는 걸 볼 수 있다. 기본 DURATION(5분)이면 결제 취소 재요청 배치(1분 간격)가
// 여러 번 돌 시간은 된다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

const DURATION = __ENV.DURATION || '5m';
const VUS = Number(__ENV.VUS || 10);
const MIN_SLEEP_SECONDS = Number(__ENV.MIN_SLEEP_SECONDS || 1);
const MAX_SLEEP_SECONDS = Number(__ENV.MAX_SLEEP_SECONDS || 3);

// access_token TTL(15분)보다 DURATION이 길어질 수 있어 payment-batch-soak.js와 같은 이유로 재로그인한다.
const SESSION_REFRESH_MS = 10 * 60 * 1000;
let session = null;
let sessionIssuedAt = 0;

const ordersCreated = new Counter('fault_orders_created_total');
const paymentSucceeded = new Counter('fault_payment_succeeded_total');
const paymentFailed = new Counter('fault_payment_failed_total');
const paymentUnknown = new Counter('fault_payment_unknown_total');
const cancelSucceeded = new Counter('fault_cancel_succeeded_total');
const cancelUnknown = new Counter('fault_cancel_unknown_total');
// HTTP 레벨 실패(5xx, 연결 실패 등)만 센다. 주입된 결제 실패/결과불명은 정상 동작이라 포함하지 않는다.
const httpFailures = new Counter('fault_http_failures_total');

export const options = {
    scenarios: {
        order_lifecycle: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
        },
    },
    thresholds: {
        fault_http_failures_total: ['count==0'],
        // 장애 주입이 실제로 섞였는지 확인한다(세 결과 전부 1건 이상). 0건이면 장애 주입이 전혀
        // 안 됐다는 뜻이므로 비율 설정(GRAB_MOCK_*_RATE)이나 DURATION/VUS를 확인해야 한다.
        fault_payment_failed_total: ['count>0'],
        fault_payment_unknown_total: ['count>0'],
        fault_cancel_unknown_total: ['count>0'],
    },
};

export function setup() {
    const dropId = Number(__ENV.DROP_ID);
    const optionId = Number(__ENV.OPTION_ID);
    if (!dropId || !optionId) {
        throw new Error('DROP_ID, OPTION_ID 환경변수가 필요합니다. 이 폴더의 seed.sql 실행 결과를 확인하세요.');
    }
    return { dropId, optionId };
}

function currentSession() {
    if (!session || Date.now() - sessionIssuedAt > SESSION_REFRESH_MS) {
        session = login('k6-buyer@loadtest.local', 'LoadTest1234!');
        sessionIssuedAt = Date.now();
    }
    return session;
}

export default function (data) {
    try {
        runIteration(data);
    } catch (e) {
        httpFailures.add(1);
    }
    sleep(randomBetween(MIN_SLEEP_SECONDS, MAX_SLEEP_SECONDS));
}

function runIteration(data) {
    const vuSession = currentSession();
    primeCookies(vuSession);

    const orderPayload = JSON.stringify({
        dropId: data.dropId,
        items: [{ optionId: data.optionId, quantity: 1 }],
        shippingAddress: {
            recipient: 'k6 테스터',
            phone: '01099990000',
            postalCode: '06236',
            address1: '서울시 강남구 테스트로 1',
        },
    });
    const orderRes = http.post(`${BASE_URL}/api/v1/orders`, orderPayload, {
        headers: authHeaders(vuSession, { 'Idempotency-Key': uuidv4() }),
    });
    const orderOk = check(orderRes, { '주문 201': (r) => r.status === 201 });
    if (!orderOk) {
        httpFailures.add(1);
        return;
    }
    ordersCreated.add(1);
    const order = JSON.parse(orderRes.body).data;

    const payRes = http.post(
        `${BASE_URL}/api/v1/orders/${order.orderId}/payments`,
        JSON.stringify({ paymentKey: `mock_${uuidv4()}`, amount: order.totalAmount }),
        { headers: authHeaders(vuSession, { 'Idempotency-Key': uuidv4() }) }
    );
    const payOk = check(payRes, {
        // 장애 주입 대상이라 FAILED·UNKNOWN도 API 레벨에선 200으로 정상 응답한다(PAYMENT.md 1.1).
        '결제 200': (r) => r.status === 200,
    });
    if (!payOk) {
        httpFailures.add(1);
        return;
    }
    const payment = JSON.parse(payRes.body).data;
    if (payment.status === 'SUCCEEDED') {
        paymentSucceeded.add(1);
        sleep(randomBetween(0.5, 2));
        const cancelRes = http.post(
            `${BASE_URL}/api/v1/orders/${order.orderId}/cancel`,
            JSON.stringify({ reason: 'GR-155 장애 주입 테스트 취소' }),
            { headers: authHeaders(vuSession, { 'Idempotency-Key': uuidv4() }) }
        );
        const cancelOk = check(cancelRes, { '취소 200': (r) => r.status === 200 });
        if (!cancelOk) {
            httpFailures.add(1);
            return;
        }
        const cancel = JSON.parse(cancelRes.body).data;
        if (cancel.refundStatus === 'SUCCEEDED') {
            cancelSucceeded.add(1);
        } else if (cancel.refundStatus === 'UNKNOWN') {
            cancelUnknown.add(1);
        }
    } else if (payment.status === 'FAILED') {
        paymentFailed.add(1);
    } else if (payment.status === 'UNKNOWN') {
        paymentUnknown.add(1);
    }
}

function randomBetween(min, max) {
    return min + Math.random() * (max - min);
}

export function teardown() {
    console.log('완료: k6/payment-fault-injection/verify.sql로 결과불명이 해소됐는지, 보정 환불이 중복 생성되지 않았는지 확인하세요.');
}
