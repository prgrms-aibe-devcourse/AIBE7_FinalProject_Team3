// PERF(GR-174): 판매자 주문 목록·상세 조회 부하. steady-read(GR-153)와 같은 성격 —
// 정확한 캐파시티가 아니라, 캐시 없이 조회가 평상시 수준 동시 조회를 버티는 하한선을
// 본다. seed.sql이 이 시나리오 전용 판매자(k6-order-seller)에 DROP 1개(재고 넉넉히)·
// 결제 완료 주문 1000건을 깔아둔 상태를 전제로 한다.
//
// 작업 내용 3(GR-174): drop-open-spike(GR-152)처럼 오픈 직후 주문이 몰리는 시점과
// 판매자 조회가 겹칠 때도 버티는지 함께 본다 — order_burst 시나리오가 실제 주문 API로
// 같은 DROP에 주문을 계속 추가하는 동안 list_read/detail_read를 돌려 지연시간이
// 늘어나는지 비교한다. ENABLE_BURST=false로 끄면 순수 steady-state 기준선만 잰다.
//
// 사전 준비: common/accounts-seed.sql, seller-order-read/seed.sql 실행 후 출력된
// drop_id/option_id를 -e로 넘긴다.
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 k6/seller-order-read/seller-order-read.js
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 -e ENABLE_BURST=false k6/seller-order-read/seller-order-read.js  # 기준선
//
// 실행 중 DB 커넥션(HikariCP) 사용량은 GR-159 Grafana 대시보드로 함께 본다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

const DURATION = __ENV.DURATION || '30s';
const LIST_VUS = Number(__ENV.LIST_VUS || 10);
const DETAIL_VUS = Number(__ENV.DETAIL_VUS || 10);
const PAGE_SIZE = 20;
const TOTAL_PAGES = 50; // seed.sql 기준 주문 1000건 / size 20
const ENABLE_BURST = (__ENV.ENABLE_BURST || 'true') !== 'false';
const BURST_RATE = Number(__ENV.BURST_RATE || 20); // 초당 신규 주문 수
const BURST_START = __ENV.BURST_START || '10s';
const BURST_DURATION = __ENV.BURST_DURATION || '10s';

const scenarios = {
    list_read: {
        executor: 'constant-vus',
        exec: 'listRead',
        vus: LIST_VUS,
        duration: DURATION,
    },
    detail_read: {
        executor: 'constant-vus',
        exec: 'detailRead',
        vus: DETAIL_VUS,
        duration: DURATION,
    },
};
if (ENABLE_BURST) {
    scenarios.order_burst = {
        executor: 'constant-arrival-rate',
        exec: 'orderBurst',
        rate: BURST_RATE,
        timeUnit: '1s',
        duration: BURST_DURATION,
        startTime: BURST_START,
        preAllocatedVUs: 20,
        maxVUs: 50,
    };
}

export const options = {
    scenarios,
    // 빈 배열(no-op) thresholds지만, k6가 시나리오 태그별로 요약을 따로 찍게 만드는 용도다.
    // list_read(목록, LATERAL 조인 1개)와 detail_read(상세, 쿼리 3~4개)는 성격이 달라서
    // 합산 p95로는 둘 중 어느 쪽도 정확히 대표하지 못한다.
    thresholds: {
        'http_req_duration{scenario:list_read}': [],
        'http_req_duration{scenario:detail_read}': [],
    },
};

export function setup() {
    const dropId = Number(__ENV.DROP_ID);
    const optionId = Number(__ENV.OPTION_ID);
    if (!dropId || !optionId) {
        throw new Error('DROP_ID, OPTION_ID 환경변수가 필요합니다. 이 폴더의 seed.sql 실행 결과를 확인하세요.');
    }
    const sellerSession = login('k6-order-seller@loadtest.local', 'LoadTest1234!');
    // login()을 두 번 연달아 부르면 첫 로그인이 남긴 XSRF-TOKEN 쿠키 때문에 두 번째
    // /auth/csrf가 새 쿠키를 안 내려준다(common/lib/auth.js 주석 참고) — 비워주고 호출한다.
    http.cookieJar().clear(BASE_URL);
    const buyerSession = login('k6-buyer@loadtest.local', 'LoadTest1234!');

    primeCookies(sellerSession);
    const listRes = http.get(`${BASE_URL}/api/v1/seller/orders?size=50`, {
        headers: authHeaders(sellerSession),
    });
    if (listRes.status !== 200) {
        throw new Error(`시드 데이터 조회 실패: status=${listRes.status} body=${listRes.body}. seller-order-read/seed.sql을 먼저 실행하세요.`);
    }
    const content = JSON.parse(listRes.body).data.content;
    if (content.length < 1) {
        throw new Error('seller-order-read/seed.sql이 만든 더미 주문이 없습니다. seed.sql을 먼저 실행하세요.');
    }
    const orderIdPool = content.map((o) => o.orderId);

    return { sellerSession, buyerSession, orderIdPool, dropId, optionId };
}

export function listRead(data) {
    primeCookies(data.sellerSession);
    const page = Math.floor(Math.random() * TOTAL_PAGES);
    const res = http.get(`${BASE_URL}/api/v1/seller/orders?page=${page}&size=${PAGE_SIZE}`, {
        headers: authHeaders(data.sellerSession),
    });
    check(res, { '목록 200': (r) => r.status === 200 });
    sleep(0.2 + Math.random() * 0.3);
}

export function detailRead(data) {
    primeCookies(data.sellerSession);
    const orderId = data.orderIdPool[Math.floor(Math.random() * data.orderIdPool.length)];
    const res = http.get(`${BASE_URL}/api/v1/seller/orders/${orderId}`, {
        headers: authHeaders(data.sellerSession),
    });
    check(res, { '상세 200': (r) => r.status === 200 });
    sleep(0.2 + Math.random() * 0.3);
}

export function orderBurst(data) {
    primeCookies(data.buyerSession);
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
        headers: authHeaders(data.buyerSession, { 'Idempotency-Key': uuidv4() }),
    });
    check(res, { '주문 201': (r) => r.status === 201 });
}

export function teardown() {
    console.log('완료: Grafana HikariCP 패널과 docker stats로 DB 커넥션·리소스 사용량을 확인하세요.');
}
