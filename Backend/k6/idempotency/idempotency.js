// 멱등성(Idempotency-Key) 검증:
//   1) concurrentSameBody — 같은 키 + 같은 본문으로 동시 요청 → 전부 201,
//      실제로는 주문이 1건만 생성됨(verify.sql로 확인)
//   2) sameKeyDifferentBody — 같은 키 + 다른 본문 → 첫 요청 201, 두 번째 요청 409
//      DUPLICATE_IDEMPOTENCY_KEY
//
// 사전 준비: common/accounts-seed.sql, idempotency/seed.sql을 실행해 이 시나리오 전용
// DROP/옵션을 만든다(stock-race와 재고 풀이 분리돼 있어 서로 영향 없음).
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 k6/idempotency/idempotency.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

const CONCURRENT_VUS = Number(__ENV.VUS || 8);

export const options = {
    scenarios: {
        concurrent_same_body: {
            executor: 'per-vu-iterations',
            vus: CONCURRENT_VUS,
            iterations: 1,
            exec: 'concurrentSameBody',
            maxDuration: '20s',
        },
        same_key_diff_body: {
            executor: 'shared-iterations',
            vus: 1,
            iterations: 1,
            exec: 'sameKeyDifferentBody',
            startTime: '5s',
            maxDuration: '10s',
        },
    },
};

function shippingAddress() {
    return {
        recipient: 'k6 테스터',
        phone: '01099990000',
        postalCode: '06236',
        address1: '서울시 강남구 테스트로 1',
    };
}

export function setup() {
    const dropId = Number(__ENV.DROP_ID);
    const optionId = Number(__ENV.OPTION_ID);
    if (!dropId || !optionId) {
        throw new Error('DROP_ID, OPTION_ID 환경변수가 필요합니다. 이 폴더의 seed.sql 실행 결과를 확인하세요.');
    }
    const session = login('k6-buyer@loadtest.local', 'LoadTest1234!');
    return {
        session,
        dropId,
        optionId,
        sharedKey: uuidv4(),
        diffBodyKey: uuidv4(),
    };
}

// 같은 키 + 같은 본문: 여러 VU가 동시에 보내도 결과는 전부 201(최초 생성 또는 replay)이고,
// 실제 생성되는 주문은 1건이어야 한다.
export function concurrentSameBody(data) {
    primeCookies(data.session);

    const payload = JSON.stringify({
        dropId: data.dropId,
        items: [{ optionId: data.optionId, quantity: 1 }],
        shippingAddress: shippingAddress(),
    });

    const res = http.post(`${BASE_URL}/api/v1/orders`, payload, {
        headers: authHeaders(data.session, { 'Idempotency-Key': data.sharedKey }),
    });

    check(res, { '동시 동일요청 → 201': (r) => r.status === 201 });
}

// 같은 키 + 다른 본문: 첫 요청은 201, 같은 키로 수량만 바꿔 다시 보내면 409.
export function sameKeyDifferentBody(data) {
    primeCookies(data.session);

    const firstPayload = JSON.stringify({
        dropId: data.dropId,
        items: [{ optionId: data.optionId, quantity: 1 }],
        shippingAddress: shippingAddress(),
    });
    const firstRes = http.post(`${BASE_URL}/api/v1/orders`, firstPayload, {
        headers: authHeaders(data.session, { 'Idempotency-Key': data.diffBodyKey }),
    });
    check(firstRes, { '최초 요청 → 201': (r) => r.status === 201 });

    const secondPayload = JSON.stringify({
        dropId: data.dropId,
        items: [{ optionId: data.optionId, quantity: 2 }],
        shippingAddress: shippingAddress(),
    });
    const secondRes = http.post(`${BASE_URL}/api/v1/orders`, secondPayload, {
        headers: authHeaders(data.session, { 'Idempotency-Key': data.diffBodyKey }),
    });
    check(secondRes, {
        '같은 키 다른 본문 → 409': (r) => r.status === 409,
        'DUPLICATE_IDEMPOTENCY_KEY': (r) =>
            r.status === 409 && JSON.parse(r.body).error.code === 'DUPLICATE_IDEMPOTENCY_KEY',
    });
}

export function teardown() {
    console.log('완료: k6/idempotency/verify.sql로 idempotency_key당 주문이 1건인지 확인하세요.');
}
