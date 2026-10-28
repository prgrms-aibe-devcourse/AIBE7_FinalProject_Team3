// GR-164: 대용량 seed에서 공개 DROP 목록 조회의 동시 접속 단계별 한계.
// drop-list.js가 조합별 쿼리 비용(VU 10 고정)을 본다면, 이 스크립트는 같은 데이터에서 동시 사용자를 늘릴 때
// 처리량이 어디서 멈추고 응답시간이 어디서 꺾이는지를 본다. GR-176·177 개선 전후를 같은 조건으로 비교하기 위한 기준선이다.
//
// TARGET으로 대상 트래픽을 고른다.
//   mix         조합을 섞은 혼합 트래픽(아래 MIX 비중)
//   <조합 이름>  combos.json의 한 조합만(예: grab_none, date_soldout)
// 단계(STEPS)마다 시나리오를 하나씩 두고 순서대로 실행해 단계별 지표가 summary에 따로 남게 한다
// ("http_req_duration{scenario:vu<N>}", "http_reqs{scenario:vu<N>}").
// 사용자는 요청 후 THINK_S초 쉬므로 VU 수 ≈ 동시 접속자 수다(쉬지 않는 스트레스가 아니다).
//
// 보통 concurrency.sh로 실행한다(Prometheus HikariCP 지표를 단계 구간별로 함께 남긴다).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from '../common/lib/auth.js';

const TARGET = __ENV.TARGET || 'mix';
const STEPS = (__ENV.STEPS || '25,50,100,200,400,800').split(',').map(Number);
const STEP_S = Number(__ENV.STEP_S || 60);
const GAP_S = Number(__ENV.GAP_S || 10);
const THINK_S = Number(__ENV.THINK_S || 1);
const combos = JSON.parse(open('./combos.json'));

// 혼합 트래픽 비중(합 100). 실측 트래픽 비율이 없어 기본 진입(날짜 정렬, 필터 없음)을 가장 크게 둔 가정값이다.
// 개선 전후 비교에서는 이 값을 바꾸지 않는다.
const MIX = {
    date_none: 30, date_category: 10, date_keyword: 10, date_soldout: 10,
    wish_none: 10, wish_category: 5, wish_keyword: 5, wish_soldout: 5,
    grab_none: 10, grab_soldout: 5,
};

const queries = Object.fromEntries(combos.map((c) => {
    const params = { ...c.params, page: '0', size: '20' };
    const qs = Object.entries(params)
        .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
        .join('&');
    return [c.name, qs];
}));
if (TARGET !== 'mix' && !queries[TARGET]) {
    throw new Error(`알 수 없는 TARGET: ${TARGET}`);
}
const mixTable = Object.entries(MIX).flatMap(([name, weight]) => Array(weight).fill(name));

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
    // 단계별 하위 지표를 summary에 남기기 위한 기록용 임계값.
    thresholds[`http_req_duration{scenario:${name}}`] = ['p(95)<600000'];
    thresholds[`http_reqs{scenario:${name}}`] = ['count>=0'];
    thresholds[`http_req_failed{scenario:${name}}`] = ['rate<=1'];
});

export const options = {
    scenarios,
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 시나리오 시작 기준 시각. concurrency.sh가 이 값으로 단계별 Prometheus 구간을 계산한다.
export function setup() {
    console.log(`K6_SCENARIO_START=${Math.floor(Date.now() / 1000)}`);
}

export function list() {
    const combo = TARGET === 'mix' ? mixTable[Math.floor(Math.random() * mixTable.length)] : TARGET;
    const res = http.get(`${BASE_URL}/api/v1/drops?${queries[combo]}`, { tags: { combo } });
    check(res, { 'status 200': (r) => r.status === 200 });
    sleep(THINK_S);
}
