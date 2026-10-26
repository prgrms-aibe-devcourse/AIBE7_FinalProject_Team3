// PERF(GR-161): 로그인 동시 폭주. 로그인 비밀번호 검증(Argon2id, 해시 1회에 약 19MiB)이
// 동시에 몰릴 때 nano 환경(힙 384MiB)이 어느 VU 단계부터 무너지는지 본다.
// 01~04는 setup()에서 한 번만 로그인하므로, 로그인 자체의 부하는 이 시나리오에서만 측정한다.
//
// 반복 1회 = 새 사용자 1명이 앱을 열고(CSRF 발급) 로그인하는 흐름이다. think time은 없다.
// 합격 기준(thresholds)은 두지 않는다. 목표 수치(NFR-006)가 아직 없고, 무너지는 지점을
// 찾는 테스트라 실패가 나와도 끝까지 실행한다. 단계별 결과는 아래 Counter와 login_duration으로 읽는다.
//
// 환경변수
//   VUS       동시 VU 수(기본 5). 단계마다 따로 실행한다: 5 → 10 → 20 → 40 (→ 80)
//   SHAPE     steady(기본): constant-vus로 DURATION 동안 계속 로그인
//             burst: per-vu-iterations로 VUS명이 같은 순간에 1번씩 로그인
//   DURATION  steady 유지 시간(기본 30s). burst에는 쓰지 않는다
//   MODE      success(기본): 공통 테스트 계정으로 로그인, 200 기대
//             fail: 없는 이메일로 로그인, 401 INVALID_CREDENTIALS 기대. 없는 계정도 더미 해시로
//                   Argon2를 실행하므로 비용은 같고, sellers 조회·JWT·Redis 저장만 빠진다
//   EMAIL, PASSWORD  MODE의 기본 계정 대신 쓸 값
//
// 사전 준비: common/accounts-seed.sql
//   k6 run -e VUS=20 Backend/k6/login-burst/login-burst.js
//   k6 run -e VUS=20 -e SHAPE=burst Backend/k6/login-burst/login-burst.js
//   k6 run -e VUS=20 -e MODE=fail Backend/k6/login-burst/login-burst.js
//
// success 모드는 로그인마다 Redis에 세션 키 2개(TTL 14일)를 남긴다. 측정이 끝나면 README 5절의 정리 절차를 따른다.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL } from '../common/lib/auth.js';

// 환경 변수를 통해 테스트 조건을 받음
const VUS = Number(__ENV.VUS || 5);
const SHAPE = __ENV.SHAPE || 'steady';
const DURATION = __ENV.DURATION || '30s';
const MODE = __ENV.MODE || 'success';

const DEFAULT_ACCOUNTS = {
    success: { email: 'k6-buyer@loadtest.local', password: 'LoadTest1234!' },
    // accounts-seed.sql이 만들지 않는 이메일이다
    fail: { email: 'k6-nobody@loadtest.local', password: 'LoadTest1234!' },
};

if (!Number.isInteger(VUS) || VUS < 1) {
    throw new Error(`VUS는 1 이상의 정수여야 합니다: ${__ENV.VUS}`);
}
if (SHAPE !== 'steady' && SHAPE !== 'burst') {
    throw new Error(`SHAPE는 steady 또는 burst여야 합니다: ${SHAPE}`);
}
if (!(MODE in DEFAULT_ACCOUNTS)) {
    throw new Error(`MODE는 success 또는 fail이어야 합니다: ${MODE}`);
}

const EMAIL = __ENV.EMAIL || DEFAULT_ACCOUNTS[MODE].email;
const PASSWORD = __ENV.PASSWORD || DEFAULT_ACCOUNTS[MODE].password;
const EXPECTED_STATUS = MODE === 'success' ? 200 : 401;

// 로그인 응답 분류. 합쳐서 로그인 시도 수가 된다(CSRF 단계에서 끝난 반복은 csrf_failed로만 센다)
const loginSuccess = new Counter('login_success_total');
const loginExpectedReject = new Counter('login_expected_reject_total');
// 모드와 맞지 않는 4xx·2xx 등(예: success인데 401, fail인데 200)
const loginUnexpected = new Counter('login_unexpected_total');
const loginServerError = new Counter('login_server_error_total');
// status 0: 요청 타임아웃(기본 60초)이나 연결 실패
const loginNoResponse = new Counter('login_no_response_total');
const csrfFailed = new Counter('csrf_failed_total');
// http_req_duration은 CSRF 요청까지 섞이므로 로그인 요청 시간만 따로 모은다
const loginDuration = new Trend('login_duration', true);

/*
부하를 발생시키는 방식 선택
1. constant-vus: 각 사용자가 요청과 응답을 마치면 다음 반복을 시작합니다. 지정 시간 동안 계속 부하를 줍니다.
2. per-vu-iterations: 각 사용자가 한 번만 실행합니다. 전체 실행에는 최대 2분을 허용합니다.
 */
const scenario = SHAPE === 'steady'
    ? { executor: 'constant-vus', vus: VUS, duration: DURATION }
    // VU가 많으면 Argon2 대기열이 길어져 마지막 응답이 늦을 수 있어 넉넉히 둔다
    : { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m' };

export const options = {
    scenarios: {
        login_burst: scenario,
    },
};

// 실제 반복할 내용
export default function () {
    // 매 반복마다 CSRF 토큰부터 발급
    const csrfRes = http.get(`${BASE_URL}/api/v1/auth/csrf`, { tags: { name: 'csrf' } });
    const xsrfCookie = csrfRes.cookies['XSRF-TOKEN'];
    if (csrfRes.status !== 204 || !xsrfCookie || !xsrfCookie[0]) {
        csrfFailed.add(1);
        return;
    }
    const xsrfToken = xsrfCookie[0].value;
    // 서버가 XSRF-TOKEN에 Secure를 붙여 http 요청에는 자동으로 다시 실리지 않는다.
    // common/lib/auth.js와 같은 이유로 값만 Secure 없이 다시 심는다
    http.cookieJar().set(BASE_URL, 'XSRF-TOKEN', xsrfToken, { path: '/' });

    const loginRes = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email: EMAIL, password: PASSWORD }),
        {
            headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrfToken },
            tags: { name: 'login' },
        }
    );

    if (loginRes.status === 0) {
        loginNoResponse.add(1);
        return;
    }
    loginDuration.add(loginRes.timings.duration);

    if (MODE === 'success' && loginRes.status === 200) {
        loginSuccess.add(1);
    } else if (MODE === 'fail' && loginRes.status === 401 && errorCode(loginRes) === 'INVALID_CREDENTIALS') {
        loginExpectedReject.add(1);
    } else if (loginRes.status >= 500) {
        loginServerError.add(1);
    } else {
        loginUnexpected.add(1);
    }

    check(loginRes, {
        [`로그인 ${EXPECTED_STATUS}`]: (r) => r.status === EXPECTED_STATUS,
        '5xx 아님': (r) => r.status < 500,
    });
}

function errorCode(res) {
    try {
        return JSON.parse(res.body).error.code;
    } catch (e) {
        return null;
    }
}

export function teardown() {
    console.log(`완료(SHAPE=${SHAPE}, MODE=${MODE}, VUS=${VUS}): docker stats·docker inspect·GC 로그와 함께 단계 결과를 기록하세요.`);
}
