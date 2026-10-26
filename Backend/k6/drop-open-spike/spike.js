// PERF-002(GR-152): 드롭 오픈 순간 스파이크. 판매 시작 직전부터 목록/상세 조회가
// 몰리다가, 오픈 시각이 되면 그중 일부가 바로 주문을 시도했을 때 서버가 죽지 않는지
// (OOM-kill, 응답 불가) 본다. 레이트리밋이 아직 없어 유입 제한 없이 그대로 받는다.
//
// SSE(stock-stream) 동시 연결은 범위 밖이다(GR-157에서 별도로 다룬다).
//
// 사전 준비: common/accounts-seed.sql, drop-open-spike/seed.sql 실행 후 출력된
// drop_id/option_id를 -e로 넘긴다. seed.sql의 sale_starts_at 간격(기본 30초)과
// OPEN_DELAY_SECONDS를 맞춰서, seed 직후 바로 이 스크립트를 실행한다.
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 k6/drop-open-spike/spike.js
//
// 실행 후 검증: k6/drop-open-spike/verify.sql (상태 전환, 재고 정합성)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

const OPEN_DELAY_SECONDS = Number(__ENV.OPEN_DELAY_SECONDS || 30);
const READ_VUS = Number(__ENV.READ_VUS || 50);
const ORDER_VUS = Number(__ENV.ORDER_VUS || 30);

const orderSuccess = new Counter('spike_order_success_total');
const orderRejected = new Counter('spike_order_rejected_total');
const orderServerError = new Counter('spike_order_server_error_total');

export const options = {
    scenarios: {
        // 오픈 전부터 오픈 이후까지 계속 목록/상세를 조회하는 트래픽.
        browsing: {
            executor: 'ramping-vus',
            exec: 'browse',
            startVUs: 0,
            stages: [
                { duration: `${OPEN_DELAY_SECONDS}s`, target: READ_VUS },
                { duration: '20s', target: READ_VUS },
            ],
            gracefulRampDown: '5s',
        },
        // 오픈 시각(OPEN_DELAY_SECONDS 경과 시점)에 한꺼번에 주문을 시도.
        // DropTransitionScheduler가 WISH->GRAB으로 바꾸는 데 최대 10초(주기) 걸릴 수 있어
        // maxDuration을 넉넉히 둔다.
        order_rush: {
            executor: 'per-vu-iterations',
            exec: 'order',
            vus: ORDER_VUS,
            iterations: 1,
            startTime: `${OPEN_DELAY_SECONDS}s`,
            maxDuration: '30s',
        },
    },
};

export function setup() {
    const dropId = Number(__ENV.DROP_ID);
    const optionId = Number(__ENV.OPTION_ID);
    if (!dropId || !optionId) {
        throw new Error('DROP_ID, OPTION_ID 환경변수가 필요합니다. 이 폴더의 seed.sql 실행 결과를 확인하세요.');
    }
    const session = login('k6-buyer@loadtest.local', 'LoadTest1234!');
    return { session, dropId, optionId };
}

export function browse(data) {
    const listRes = http.get(`${BASE_URL}/api/v1/drops?size=20`);
    check(listRes, { '목록 200': (r) => r.status === 200 });

    const detailRes = http.get(`${BASE_URL}/api/v1/drops/${data.dropId}`);
    check(detailRes, { '상세 200 또는 404(아직 비공개 전환 전)': (r) => r.status === 200 || r.status === 404 });

    sleep(Math.random() * 0.5);
}

export function order(data) {
    primeCookies(data.session);

    const payload = JSON.stringify({
        dropId: data.dropId,
        items: [{ optionId: data.optionId, quantity: 1 }],
        shippingAddress: {
            recipient: 'k6 테스터',
            phone: '01099990000',
            postalCode: '06236',
            address1: '서울시 강남구 테스트로 1',
        },
    });

    const res = http.post(`${BASE_URL}/api/v1/orders`, payload, {
        headers: authHeaders(data.session, { 'Idempotency-Key': uuidv4() }),
    });

    check(res, { '5xx 아님': (r) => r.status < 500 });
    if (res.status === 201) {
        orderSuccess.add(1);
    } else if (res.status >= 500) {
        orderServerError.add(1);
    } else {
        orderRejected.add(1);
    }
}

export function teardown() {
    console.log('완료: docker stats 로그(서버 재시작 여부)와 verify.sql로 재고 정합성을 확인하세요.');
}
