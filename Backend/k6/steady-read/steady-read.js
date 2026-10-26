// PERF-003(GR-153): 평상시(드롭 진행 중 내내) 걸리는 목록/상세 조회 부하.
// 조회 캐시(Redis)는 아직 없어서(TECHSTACK.md 87절) 이 테스트가 캐시 없이
// DB가 얼마나 버티는지를 측정해, 병목이 확인되면 캐시 도입 판단 근거로 쓴다.
//
// 두 패턴을 steady-state(ramping 아님)로 동시에 유지한다:
//   - hot_read: 인기 드롭 1개를 반복 상세조회 (같은 행에 쏠림)
//   - random_read: 목록 조회 후 매번 다른 드롭을 상세조회 (분산)
//
// 사전 준비: common/accounts-seed.sql, steady-read/seed.sql 실행.
//   k6 run k6/steady-read/steady-read.js
//
// 실행 중 DB 커넥션(HikariCP) 사용량은 GR-159 Grafana 대시보드로 함께 본다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from '../common/lib/auth.js';

const DURATION = __ENV.DURATION || '30s';
const HOT_VUS = Number(__ENV.HOT_VUS || 10);
const RANDOM_VUS = Number(__ENV.RANDOM_VUS || 10);
const KEYWORD = 'k6 평상시조회 상품';

export const options = {
    scenarios: {
        hot_read: {
            executor: 'constant-vus',
            exec: 'hotRead',
            vus: HOT_VUS,
            duration: DURATION,
        },
        random_read: {
            executor: 'constant-vus',
            exec: 'randomRead',
            vus: RANDOM_VUS,
            duration: DURATION,
        },
    },
};

export function setup() {
    const res = http.get(`${BASE_URL}/api/v1/drops?keyword=${encodeURIComponent(KEYWORD)}&size=20`);
    if (res.status !== 200) {
        throw new Error(`시드 데이터 조회 실패: status=${res.status} body=${res.body}. steady-read/seed.sql을 먼저 실행하세요.`);
    }
    const content = JSON.parse(res.body).data.content;
    if (content.length < 2) {
        throw new Error('steady-read/seed.sql이 만든 더미 DROP이 부족합니다. seed.sql을 먼저 실행하세요.');
    }
    const sorted = content.slice().sort((a, b) => a.name.localeCompare(b.name));
    return {
        hotDropId: sorted[0].dropId,
        poolDropIds: sorted.map((d) => d.dropId),
    };
}

export function hotRead(data) {
    const detailRes = http.get(`${BASE_URL}/api/v1/drops/${data.hotDropId}`);
    check(detailRes, { '인기 드롭 상세 200': (r) => r.status === 200 });

    const stockRes = http.get(`${BASE_URL}/api/v1/drops/${data.hotDropId}/stocks`);
    check(stockRes, { '인기 드롭 재고 200': (r) => r.status === 200 });

    sleep(0.2 + Math.random() * 0.3);
}

export function randomRead(data) {
    const listRes = http.get(`${BASE_URL}/api/v1/drops?keyword=${encodeURIComponent(KEYWORD)}&size=20`);
    check(listRes, { '목록 200': (r) => r.status === 200 });

    const dropId = data.poolDropIds[Math.floor(Math.random() * data.poolDropIds.length)];
    const detailRes = http.get(`${BASE_URL}/api/v1/drops/${dropId}`);
    check(detailRes, { '랜덤 드롭 상세 200': (r) => r.status === 200 });

    sleep(0.2 + Math.random() * 0.3);
}

export function teardown() {
    console.log('완료: Grafana HikariCP 패널과 docker stats로 DB 커넥션·리소스 사용량을 확인하세요.');
}
