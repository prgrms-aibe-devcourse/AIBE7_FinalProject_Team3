// GR-164: 대용량 seed에서 공개 DROP 목록 조합별 응답시간.
// combos.json의 조합(날짜/인기 WISH/인기 GRAB 정렬 × 필터 없음/categoryId/keyword/soldOut)을
// 하나씩 순서대로 같은 부하로 돌려 조합끼리 서로 간섭하지 않게 하고, 조합별 p95를 따로 남긴다.
//
// 사전 준비: common/accounts-seed.sql, drop-list-scale/seed.sql 실행. explain.sh와 동시에 돌리지 않는다
// (auto_explain ANALYZE가 켜져 있으면 응답시간이 부풀려진다).
//   docker run --rm -i --network backend_default -v "$PWD/k6:/k6" -w /k6 \
//     -e BASE_URL=http://backend:8080 grafana/k6 run \
//     --summary-export drop-list-scale/out/<이름>/k6-summary.json drop-list-scale/drop-list.js
// host.docker.internal 경유는 Docker Desktop 포트 포워딩에서 간헐적인 연결 타임아웃(dial: i/o timeout)이 나
// 측정값에 섞이므로, compose 네트워크 안에서 backend 컨테이너로 직접 보낸다.
//
// 조합별 p95는 summary의 "http_req_duration{scenario:<조합>}"이다.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../common/lib/auth.js';

const VUS = Number(__ENV.VUS || 10);
const DURATION_S = Number(__ENV.DURATION_S || 20);
const GAP_S = 2;
const combos = JSON.parse(open('./combos.json'));

// 조합마다 시나리오를 하나씩 두고 startTime을 밀어 순서대로 실행한다.
// thresholds에 시나리오별 하위 지표를 걸어 두어야 summary에 조합별 p95가 따로 나온다(기준값은 기록용이라 느슨하게 둔다).
const scenarios = {};
const thresholds = {};
combos.forEach((c, i) => {
    scenarios[c.name] = {
        executor: 'constant-vus',
        exec: 'list',
        vus: VUS,
        duration: `${DURATION_S}s`,
        startTime: `${i * (DURATION_S + GAP_S)}s`,
        env: { COMBO: c.name },
    };
    thresholds[`http_req_duration{scenario:${c.name}}`] = ['p(95)<60000'];
});

export const options = {
    scenarios,
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const queries = Object.fromEntries(combos.map((c) => {
    const params = { ...c.params, page: '0', size: '20' };
    const qs = Object.entries(params)
        .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
        .join('&');
    return [c.name, qs];
}));

export function list() {
    const res = http.get(`${BASE_URL}/api/v1/drops?${queries[__ENV.COMBO]}`);
    check(res, { 'status 200': (r) => r.status === 200 });
}
