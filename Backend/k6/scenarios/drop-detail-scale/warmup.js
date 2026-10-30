// 측정 전 JVM 워밍업(GR-181). GR-176 재측정에서 쿼리가 같은 조합도 회차마다 p95가 11~42% 흔들렸고,
// 기준선은 backend 재시작 직후, 재측정은 여러 측정 뒤라 워밍업 차이가 원인으로 추정됐다. 측정마다 같은 워밍업을 거쳐 이 편차를 줄인다.
// 여러 상품의 상세·재고를 쉬지 않고 60초 부른다. 결과는 버린다.
import { run } from './combos.js';

export const options = {
    scenarios: {
        warmup: { executor: 'constant-vus', vus: Number(__ENV.VUS || 20), duration: __ENV.DURATION || '60s' },
    },
    summaryTrendStats: ['med', 'p(95)'],
};

export default function () {
    run('pair_random');
}
