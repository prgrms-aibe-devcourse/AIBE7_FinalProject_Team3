// GR-181: 상세·재고 조회의 동시 접속 단계별 한계. drop-list-scale/concurrency.sh가 SCRIPT로 이 파일을 실행한다.
// TARGET은 combos.js의 조합 이름이다(예: pair_hot = 인기 상품 상세를 보던 사용자들이 SSE 알림마다 상세+재고를 다시 부르는 상황).
// 단계(STEPS)마다 시나리오를 하나씩 두고 순서대로 실행하며, 사용자는 한 번 조회한 뒤 THINK_S초 쉰다(VU ≈ 동시 접속자).
// 시나리오 이름(vu<N>)·K6_SCENARIO_START 출력 규약은 drop-list-scale/concurrency.js와 같다.
//   STEPS=25,50,100,200,400,800 SCRIPT=drop-detail-scale/detail-concurrency.js OUT_BASE=k6/drop-detail-scale/out \
//     k6/drop-list-scale/concurrency.sh <이름> pair_hot pair_random
import { sleep } from 'k6';
import { COMBOS, run } from './combos.js';

const TARGET = __ENV.TARGET || 'pair_hot';
const STEPS = (__ENV.STEPS || '25,50,100,200,400,800').split(',').map(Number);
const STEP_S = Number(__ENV.STEP_S || 60);
const GAP_S = Number(__ENV.GAP_S || 10);
const THINK_S = Number(__ENV.THINK_S || 1);
if (!COMBOS[TARGET]) {
    throw new Error(`알 수 없는 TARGET: ${TARGET}`);
}

const scenarios = {};
const thresholds = {};
STEPS.forEach((vus, i) => {
    const name = `vu${vus}`;
    scenarios[name] = {
        executor: 'constant-vus',
        exec: 'list',
        vus,
        duration: `${STEP_S}s`,
        startTime: `${i * (STEP_S + GAP_S)}s`,
        gracefulStop: '5s',
    };
    thresholds[`http_req_duration{scenario:${name}}`] = ['p(95)<600000'];
    thresholds[`http_reqs{scenario:${name}}`] = ['count>=0'];
    thresholds[`http_req_failed{scenario:${name}}`] = ['rate<=1'];
});

export const options = {
    scenarios,
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    console.log(`K6_SCENARIO_START=${Math.floor(Date.now() / 1000)}`);
}

// concurrency.sh가 exec 이름을 가정하지 않지만 drop-list-scale과 맞춰 list로 둔다.
export function list() {
    run(TARGET);
    sleep(THINK_S);
}
