// PERF-xxx(GR-154): 평상시 수준의 주문·결제·취소 트래픽을 장시간(기본 2시간)
// 유지해, PaymentBatchService(진행 중 결제 정리 → 결제 대기 만료 → 결제 취소
// 재요청 → 보정 환불)가 몇 시간 동안 계속 돌 때 메모리·DB 커넥션 누수가 없는지
// 본다. 짧은 스파이크 시나리오로는 이런 누수를 못 본다(GR-149와 같은 전제).
//
// 실제 토스 샌드박스를 몇 시간 동안 때리면 레이트리밋·키 정지로 이어질 수 있어,
// 백엔드를 MockPaymentGateway로 띄운 상태에서 돌린다. nano(512MB)는 기본 트래픽만으로도
// 몇 분 안에 OOM-kill돼 장시간 실행이 안 되므로(result.md 참고) micro로 돌린다:
//   GRAB_PAYMENT_MOCK_GATEWAY_ENABLED=true docker compose -f compose.yaml -f compose.loadtest-micro.yaml up --build
//
// 사전 준비: common/accounts-seed.sql, payment-batch-soak/seed.sql 실행 후
// 출력된 drop_id/option_id를 -e로 넘긴다.
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 k6/payment-batch-soak/payment-batch-soak.js
// 스모크 테스트(설계 검증)는 짧은 DURATION으로 돌린다:
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 -e DURATION=2m k6/payment-batch-soak/payment-batch-soak.js
//
// 각 반복은 주문을 하나 만들고, 그중 절반만 즉시 결제를 승인한다(사용자가 결제창까지
// 가는 경우). 승인한 것 중 30%(전체의 약 15%)는 바로 취소한다. 나머지 절반은 confirm을
// 호출하지 않고 그대로 둬서 payment_expires_at(10분 고정)이 지나면 PaymentBatchScheduler
// (10초 주기)가 만료 처리하게 한다 — 배치가 매 주기 꾸준히 일거리를 갖게 하는 게 목적이다.
//
// 실행 중 메모리·커넥션 추이는 이 폴더의 monitor-resources.sh로 별도 기록한다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

const DURATION = __ENV.DURATION || '2h';
const VUS = Number(__ENV.VUS || 3);
const MIN_SLEEP_SECONDS = Number(__ENV.MIN_SLEEP_SECONDS || 2);
const MAX_SLEEP_SECONDS = Number(__ENV.MAX_SLEEP_SECONDS || 5);
// 결제까지 가는 비율과, 결제한 것 중 취소하는 비율. 평상시 수준을 흉내 낸 임의 값이라
// 정확한 운영 전환율을 반영하지는 않는다.
const CONFIRM_RATE = Number(__ENV.CONFIRM_RATE || 0.5);
const CANCEL_RATE_OF_CONFIRMED = Number(__ENV.CANCEL_RATE_OF_CONFIRMED || 0.3);

// access_token TTL이 15분(application.yml grab.auth.access-token.ttl)이라, VU당 로그인을
// 한 번만 하면 soak 중간에 전부 401로 떨어진다(실제로 16분 스모크에서 재현 확인). 여유를 두고
// 10분마다 다시 로그인한다.
const SESSION_REFRESH_MS = 10 * 60 * 1000;
let session = null;
let sessionIssuedAt = 0;

const ordersCreated = new Counter('soak_orders_created_total');
const paymentsConfirmed = new Counter('soak_payments_confirmed_total');
const ordersCanceled = new Counter('soak_orders_canceled_total');
const soakFailures = new Counter('soak_failures_total');

export const options = {
    scenarios: {
        order_lifecycle: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
        },
    },
    thresholds: {
        soak_failures_total: ['count==0'],
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

// VU마다 로그인 세션을 두고, 10분이 지나면(access_token 만료 전) 다시 로그인한다.
function currentSession() {
    if (!session || Date.now() - sessionIssuedAt > SESSION_REFRESH_MS) {
        session = login('k6-buyer@loadtest.local', 'LoadTest1234!');
        sessionIssuedAt = Date.now();
    }
    return session;
}

export default function (data) {
    // currentSession()의 login()이 던지는 예외(서버 다운, CSRF 실패 등)를 여기서 막지 않으면
    // sleep 없이 바로 다음 반복으로 넘어가 죽은 서버에 재시도를 무한히 퍼붓는다(실제로 16분 soak
    // 도중 백엔드가 OOM-kill된 뒤 초당 2500회 이상 재시도가 발생한 걸 확인했다). 장시간 실행에서
    // 백엔드가 잠깐 멈췄다 돌아오는 상황을 버텨야 하므로, 모든 예외를 여기서 잡고 반드시 sleep한다.
    try {
        runIteration(data);
    } catch (e) {
        soakFailures.add(1);
    }
    sleep(randomSleep());
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
        soakFailures.add(1);
        return;
    }
    ordersCreated.add(1);
    const order = JSON.parse(orderRes.body).data;

    if (Math.random() < CONFIRM_RATE) {
        sleep(randomBetween(0.5, 2));
        const payRes = http.post(
            `${BASE_URL}/api/v1/orders/${order.orderId}/payments`,
            JSON.stringify({ paymentKey: `mock_${uuidv4()}`, amount: order.totalAmount }),
            { headers: authHeaders(vuSession, { 'Idempotency-Key': uuidv4() }) }
        );
        const payOk = check(payRes, {
            '결제 200': (r) => r.status === 200,
            '결제 SUCCEEDED': (r) => JSON.parse(r.body).data.status === 'SUCCEEDED',
        });
        if (!payOk) {
            soakFailures.add(1);
        } else {
            paymentsConfirmed.add(1);
            if (Math.random() < CANCEL_RATE_OF_CONFIRMED) {
                sleep(randomBetween(0.5, 2));
                const cancelRes = http.post(
                    `${BASE_URL}/api/v1/orders/${order.orderId}/cancel`,
                    JSON.stringify({ reason: 'GR-154 soak 테스트 취소' }),
                    { headers: authHeaders(vuSession, { 'Idempotency-Key': uuidv4() }) }
                );
                const cancelOk = check(cancelRes, {
                    '취소 200': (r) => r.status === 200,
                    '취소 CANCELED': (r) => JSON.parse(r.body).data.status === 'CANCELED',
                });
                if (!cancelOk) {
                    soakFailures.add(1);
                } else {
                    ordersCanceled.add(1);
                }
            }
        }
    }
    // CONFIRM_RATE에 안 걸린 나머지는 confirm을 호출하지 않고 그대로 둬서
    // payment_expires_at이 지나면 PaymentBatchScheduler가 만료 처리하게 한다.
}

function randomSleep() {
    return randomBetween(MIN_SLEEP_SECONDS, MAX_SLEEP_SECONDS);
}

function randomBetween(min, max) {
    return min + Math.random() * (max - min);
}

export function teardown() {
    console.log('완료: k6/payment-batch-soak/verify.sql로 재고 정합성을, docker stats/monitor-resources.sh 로그로 메모리·CPU 추이를 확인하세요.');
}
