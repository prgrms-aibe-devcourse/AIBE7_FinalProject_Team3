// 결제 대기 만료 + 취소 동시 발생 시나리오 1/3: 주문을 1건 만든다.
// k6은 DB 드라이버가 없어 payment_expires_at을 직접 당길 수 없으므로,
// 01-create-order.js → 02-backdate.sql → 03-cancel-order.js 순서로 3단계에 나눠 실행한다.
//
//   k6 run -e DROP_ID=1 -e OPTION_ID=1 k6/expiry/01-create-order.js
//   (콘솔에 출력된 ORDER_ID를 다음 단계에 쓴다)
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';
import { uuidv4 } from '../common/lib/util.js';

export const options = { vus: 1, iterations: 1 };

export function setup() {
    const dropId = Number(__ENV.DROP_ID);
    const optionId = Number(__ENV.OPTION_ID);
    if (!dropId || !optionId) {
        throw new Error('DROP_ID, OPTION_ID 환경변수가 필요합니다. 이 폴더의 seed.sql 실행 결과를 확인하세요.');
    }
    return { session: login('k6-buyer@loadtest.local', 'LoadTest1234!'), dropId, optionId };
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

    check(res, { '201 생성': (r) => r.status === 201 });

    const body = JSON.parse(res.body);
    console.log(`ORDER_ID=${body.data.orderId}`);
}
