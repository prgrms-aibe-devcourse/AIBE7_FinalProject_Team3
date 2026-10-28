// PERF-xxx(GR-156): HikariCP 풀 크기(application.yml에 미지정 → 기본값 10)보다
// 뚜렷이 많은 동시 요청을 주문(쓰기)·목록/상세(읽기) API에 걸어, 커넥션이
// 바닥났을 때 요청이 큐에서 대기하다 처리되는지, 아니면 타임아웃/5xx로
// 실패하는지 확인한다.
//
// order_burst(쓰기)+read_burst(읽기) VU 합이 풀 크기 10보다 뚜렷이 많도록
// constant-vus로 동시 유지한다.
//
// 사전 준비: common/accounts-seed.sql, connection-pool-exhaustion/seed.sql 실행 후
// 출력된 drop_id/option_id를 -e로 넘긴다.
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 k6/connection-pool-exhaustion/pool-exhaustion.js
//
// 실행 중 HikariCP 활성/대기 커넥션 수는 GR-159 Grafana 대시보드(또는
// /actuator/prometheus의 hikaricp_connections_active, hikaricp_connections_pending)로 본다.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

const DURATION = __ENV.DURATION || '20s';
const ORDER_VUS = Number(__ENV.ORDER_VUS || 15);
const READ_VUS = Number(__ENV.READ_VUS || 15);
const exhaustionFailures = new Counter('pool_exhaustion_failures_total');

export const options = {
    scenarios: {
        order_burst: {
            executor: 'constant-vus',
            exec: 'orderBurst',
            vus: ORDER_VUS,
            duration: DURATION,
        },
        read_burst: {
            executor: 'constant-vus',
            exec: 'readBurst',
            vus: READ_VUS,
            duration: DURATION,
        },
    },
    thresholds: {
        pool_exhaustion_failures_total: ['count==0'],
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

export function orderBurst(data) {
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

    const ok = check(res, {
        '주문 201(성공) 또는 422(재고부족)': (r) => r.status === 201 || r.status === 422,
        '422면 INSUFFICIENT_STOCK': (r) => {
            if (r.status !== 422) return true;
            return JSON.parse(r.body).error.code === 'INSUFFICIENT_STOCK';
        },
    });
    if (!ok) {
        exhaustionFailures.add(1);
    }
}

export function readBurst(data) {
    const listRes = http.get(`${BASE_URL}/api/v1/drops?keyword=${encodeURIComponent('k6 커넥션풀 상품')}&size=5`);
    const listOk = check(listRes, { '목록 200': (r) => r.status === 200 });
    if (!listOk) {
        exhaustionFailures.add(1);
    }

    const detailRes = http.get(`${BASE_URL}/api/v1/drops/${data.dropId}`);
    const detailOk = check(detailRes, { '상세 200': (r) => r.status === 200 });
    if (!detailOk) {
        exhaustionFailures.add(1);
    }
}

export function teardown() {
    console.log('완료: /actuator/prometheus의 hikaricp_connections_active/pending, Grafana HikariCP 패널로 대기·고갈 여부를 확인하세요.');
}
