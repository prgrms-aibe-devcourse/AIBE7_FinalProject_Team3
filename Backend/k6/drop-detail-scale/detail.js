// GR-181: 대용량 seed에서 공개 DROP 상세·재고 조회의 조합별 응답시간.
// drop-list-scale/drop-list.js와 같은 방식으로 조합(combos.js)을 VU 10 × 20초씩 순서대로 돌려 조합별 지표를 따로 남긴다.
// http_req_duration은 요청 단위, pair_*의 iteration_duration은 상세+재고 한 쌍(SSE 재조회 한 번)의 시간이다.
//
// 사전 준비: drop-list-scale/seed.sql → targets.sh. 보통 measure.sh로 실행한다.
//   docker run --rm -i --network backend_default -v "$PWD/k6:/k6" -w /k6 -e BASE_URL=http://backend:8080 \
//     grafana/k6 run --summary-export drop-detail-scale/out/<이름>/k6-summary.json drop-detail-scale/detail.js
import { COMBOS, run } from './combos.js';

const VUS = Number(__ENV.VUS || 10);
const DURATION_S = Number(__ENV.DURATION_S || 20);
const GAP_S = 2;

const scenarios = {};
// 부하 없는 측정이라 실패 응답이 있으면 결과를 쓰지 않는다. 실패 시 k6가 0이 아닌 코드로 끝나 measure.sh·latency.sh도 멈춘다.
const thresholds = { http_req_failed: ['rate==0'], checks: ['rate==1'] };
Object.keys(COMBOS).forEach((name, i) => {
    scenarios[name] = {
        executor: 'constant-vus',
        exec: 'measure',
        vus: VUS,
        duration: `${DURATION_S}s`,
        startTime: `${i * (DURATION_S + GAP_S)}s`,
        gracefulStop: '2s',
        env: { COMBO: name },
    };
    // 조합별 하위 지표를 summary에 남기기 위한 기록용 임계값.
    thresholds[`http_req_duration{scenario:${name}}`] = ['p(95)<60000'];
    thresholds[`iteration_duration{scenario:${name}}`] = ['p(95)<60000'];
});

export const options = {
    scenarios,
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function measure() {
    run(__ENV.COMBO);
}
