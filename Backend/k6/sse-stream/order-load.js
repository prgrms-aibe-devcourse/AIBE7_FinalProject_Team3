// GR-157: SSE 부하 중 재고 변경 원천이 되는 주문 트래픽. 초당 주문 수를 고정해 넣는다(REALTIME_STOCK.md 13.2).
// 이벤트를 직접 발행하지 않고 실제 주문 생성(재고 선점 → 커밋 후 STOCK 변경 발행)으로 변경을 만든다.
// 결제는 하지 않는다. PG 호출이 섞이면 주문 지연이 오염된다.
//
// SSE 없이 이 스크립트만 돌린 결과가 "SSE 비활성 기준선"이다(주문 p95 비교용).
// 주문 성공마다 ORDER <응답 수신 epoch ms> <dropId> <요청 시작 epoch ms> 한 줄을 남기며, analyze.mjs가 이 로그로 전달 지연을 계산한다.
// 커밋은 요청 시작과 응답 수신 사이에 있다(GR-200: 응답보다 먼저 도착한 알림을 유실과 구분하는 데 쓴다).
//
//   k6 run --console-output=Backend/k6/sse-stream/out/orders.log Backend/k6/sse-stream/order-load.js
//   (평시) RATE=5 / (피크) RATE=50 HOT_RATIO=0.8 — 피크 50회/초 중 인기 상품 40회/초
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

const RATE = Number(__ENV.RATE || 5);
const DURATION = __ENV.DURATION || '5m';
const HOT_RATIO = Number(__ENV.HOT_RATIO || 0.8);
const KEYWORD = 'k6 SSE 상품';

const orderRejected = new Counter('sse_order_rejected_total');
const orderServerError = new Counter('sse_order_server_error_total');

export const options = {
    thresholds: {
        // 판정 기준(SLA)이 아니라, setup 요청을 뺀 주문만의 응답시간을 요약에 따로 내기 위한 빈 threshold다.
        'http_req_duration{name:order}': [],
        // VU가 모자라 시작하지 못한 주문이 있으면 목표 주문률을 못 낸 회차라 실패로 본다.
        dropped_iterations: ['count==0'],
    },
    scenarios: {
        orders: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Math.max(10, RATE),
            maxVUs: Math.max(50, RATE * 4),
        },
    },
};

export function setup() {
    // 로그인을 먼저 한다. k6 v2는 Secure 쿠키도 http로 다시 보내므로, 다른 요청이 받아 둔 XSRF-TOKEN이
    // 쿠키 저장소에 있으면 /auth/csrf가 새 쿠키를 내려주지 않아 login()이 실패한다.
    const session = login('k6-buyer@loadtest.local', 'LoadTest1234!');
    const res = http.get(`${BASE_URL}/api/v1/drops?keyword=${encodeURIComponent(KEYWORD)}&size=100`);
    if (res.status !== 200) {
        throw new Error(`시드 데이터 조회 실패: status=${res.status}. sse-stream/seed.sql을 먼저 실행하세요.`);
    }
    const sorted = JSON.parse(res.body).data.content.slice().sort((a, b) => a.name.localeCompare(b.name));
    const drops = sorted.map((d) => {
        const stockRes = http.get(`${BASE_URL}/api/v1/drops/${d.dropId}/stocks`);
        return { dropId: d.dropId, optionId: JSON.parse(stockRes.body).data.options[0].optionId };
    });
    return { session, hot: drops[0], others: drops.slice(1) };
}

export default function (data) {
    primeCookies(data.session);
    const target = Math.random() < HOT_RATIO
        ? data.hot
        : data.others[Math.floor(Math.random() * data.others.length)];

    const payload = JSON.stringify({
        dropId: target.dropId,
        items: [{ optionId: target.optionId, quantity: 1 }],
        shippingAddress: {
            recipient: 'k6 테스터',
            phone: '01099990000',
            postalCode: '06236',
            address1: '서울시 강남구 테스트로 1',
        },
    });

    const startedAt = Date.now();
    const res = http.post(`${BASE_URL}/api/v1/orders`, payload, {
        headers: authHeaders(data.session, { 'Idempotency-Key': uuidv4() }),
        tags: { name: 'order' },
    });

    check(res, { '주문 201': (r) => r.status === 201 });
    if (res.status === 201) {
        // 커밋은 응답 전에 끝나므로 응답 수신 시각을 커밋 시각의 근사로 쓴다.
        console.log(`ORDER ${Date.now()} ${target.dropId} ${startedAt}`);
    } else if (res.status >= 500) {
        orderServerError.add(1);
    } else {
        orderRejected.add(1);
    }
}
