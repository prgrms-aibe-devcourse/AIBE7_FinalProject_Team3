// STOCK-001 / NFR-003: 재고가 적은 옵션 1개에 다수 VU가 동시에 주문해도
// 재고 이상으로 팔리지 않는지 검증한다.
//
// 사전 준비: common/accounts-seed.sql, stock-race/seed.sql 실행 후 출력된
// drop_id/option_id를 -e로 넘긴다.
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 k6/stock-race/stock-race.js
//
// 실행 후 검증: k6/stock-race/verify.sql (옵션 재고 음수 여부, 총 판매량 <= 초기재고)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

const VUS = Number(__ENV.VUS || 20);
// seed.sql의 total_quantity와 맞춘다. 재고보다 적게 성공하면(0건 등) 재고 로직이 아예
// 안 먹힌 경우라서, 개별 체크만으로는 못 잡고 이 threshold로 잡는다.
const EXPECTED_STOCK = Number(__ENV.EXPECTED_STOCK || 5);
const successCount = new Counter('stock_race_success_total');

export const options = {
    scenarios: {
        stock_race: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '30s',
        },
    },
    thresholds: {
        stock_race_success_total: [`count==${Math.min(VUS, EXPECTED_STOCK)}`],
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

export default function (data) {
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

    check(res, {
        '201(성공) 또는 422(재고부족)': (r) => r.status === 201 || r.status === 422,
        '422면 INSUFFICIENT_STOCK': (r) => {
            if (r.status !== 422) return true;
            return JSON.parse(r.body).error.code === 'INSUFFICIENT_STOCK';
        },
    });
    if (res.status === 201) {
        successCount.add(1);
    }
}

export function teardown() {
    console.log('완료: k6/stock-race/verify.sql로 재고 음수 여부·총 판매량을 확인하세요.');
}
