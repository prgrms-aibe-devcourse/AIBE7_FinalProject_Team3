// 상세·재고 측정 조합(GR-181). detail.js와 detail-concurrency.js가 함께 쓴다.
// 상세 화면의 SSE 재조회는 알림 하나에 상세와 재고를 둘 다 부르므로(REALTIME_STOCK.md 4절) pair_*로 한 쌍을 묶는다.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../common/lib/auth.js';

export const targets = JSON.parse(open('./out/targets.json'));

const randomId = () => targets.pool[Math.floor(Math.random() * targets.pool.length)];
const detail = (id, combo) => http.get(`${BASE_URL}/api/v1/drops/${id}`, { tags: { combo, api: 'detail' } });
const stock = (id, combo) => http.get(`${BASE_URL}/api/v1/drops/${id}/stocks`, { tags: { combo, api: 'stock' } });

export const COMBOS = {
    detail_hot: () => [detail(targets.hot, 'detail_hot')],
    detail_simple: () => [detail(targets.simple, 'detail_simple')],
    detail_random: () => [detail(randomId(), 'detail_random')],
    stock_hot: () => [stock(targets.hot, 'stock_hot')],
    stock_simple: () => [stock(targets.simple, 'stock_simple')],
    stock_random: () => [stock(randomId(), 'stock_random')],
    pair_hot: () => [detail(targets.hot, 'pair_hot'), stock(targets.hot, 'pair_hot')],
    pair_random: () => {
        const id = randomId();
        return [detail(id, 'pair_random'), stock(id, 'pair_random')];
    },
};

export function run(combo) {
    for (const res of COMBOS[combo]()) {
        check(res, { 'status 200': (r) => r.status === 200 });
    }
}
