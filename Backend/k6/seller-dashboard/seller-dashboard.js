// PERF(GR-173): 판매자 대시보드 요약·DROP별 통계 조회 부하. steady-read(GR-153)와 같은
// 성격 — 정확한 캐파시티가 아니라, 캐시 없이 집계 쿼리가 평상시 수준 동시 조회를 버티는
// 하한선을 본다. seed.sql이 이 시나리오 전용 판매자(k6-dashboard-seller)에 DROP 200개·
// DROP당 주문 5건(결제 완료)·위시 20명을 깔아둔 상태를 전제로 한다(대시보드 API는
// 판매자 전체를 집계하므로, 다른 soak 시나리오와 데이터가 섞이지 않게 전용 계정을 쓴다).
//
// 사전 준비: common/accounts-seed.sql, seller-dashboard/seed.sql 실행.
//   k6 run k6/seller-dashboard/seller-dashboard.js
//
// 두 패턴을 steady-state(ramping 아님)로 동시에 유지한다:
//   - summary_read: 대시보드 요약(DROP·주문·결제 상태별 건수 + 재고 합계, 집계 5종)을 반복 조회
//   - drop_stats_read: DROP별 통계(페이지네이션, DROP당 LATERAL 조인 2개)를 매번 다른 페이지로 조회
//
// 실행 중 DB 커넥션(HikariCP) 사용량은 GR-159 Grafana 대시보드로 함께 본다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, login, primeCookies, authHeaders } from '../common/lib/auth.js';

const DURATION = __ENV.DURATION || '30s';
const SUMMARY_VUS = Number(__ENV.SUMMARY_VUS || 10);
const DROP_STATS_VUS = Number(__ENV.DROP_STATS_VUS || 10);
const PAGE_SIZE = 20;
const TOTAL_PAGES = 10; // seed.sql 기준 DROP 200개 / size 20

export const options = {
    scenarios: {
        summary_read: {
            executor: 'constant-vus',
            exec: 'summaryRead',
            vus: SUMMARY_VUS,
            duration: DURATION,
        },
        drop_stats_read: {
            executor: 'constant-vus',
            exec: 'dropStatsRead',
            vus: DROP_STATS_VUS,
            duration: DURATION,
        },
    },
    // 빈 배열(no-op) thresholds지만, k6가 시나리오 태그별로 요약을 따로 찍게 만드는 용도다.
    // summary_read(가벼운 집계 5종)와 drop_stats_read(findDropStats, LATERAL 조인 2개)의
    // 지연시간 특성이 달라서, 합산 p95로는 findDropStats 단독 성능을 판단할 수 없다.
    thresholds: {
        'http_req_duration{scenario:summary_read}': [],
        'http_req_duration{scenario:drop_stats_read}': [],
    },
};

export function setup() {
    const session = login('k6-dashboard-seller@loadtest.local', 'LoadTest1234!');
    return { session };
}

export function summaryRead(data) {
    primeCookies(data.session);
    const res = http.get(`${BASE_URL}/api/v1/seller/dashboard/summary`, {
        headers: authHeaders(data.session),
    });
    check(res, { '요약 200': (r) => r.status === 200 });
    sleep(0.2 + Math.random() * 0.3);
}

export function dropStatsRead(data) {
    primeCookies(data.session);
    const page = Math.floor(Math.random() * TOTAL_PAGES);
    const res = http.get(`${BASE_URL}/api/v1/seller/dashboard/drops?page=${page}&size=${PAGE_SIZE}`, {
        headers: authHeaders(data.session),
    });
    check(res, { 'DROP 통계 200': (r) => r.status === 200 });
    sleep(0.2 + Math.random() * 0.3);
}

export function teardown() {
    console.log('완료: Grafana HikariCP 패널과 docker stats로 DB 커넥션·리소스 사용량을 확인하세요.');
}
