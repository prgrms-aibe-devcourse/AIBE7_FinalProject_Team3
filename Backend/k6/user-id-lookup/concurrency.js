// GR-201: 인증 요청의 회원 ID 변환(public_id → users.id) 비용을 동시 접속 단계별로 본다.
// 내 정보 조회(GET /api/v1/users/me)는 ID 변환 트랜잭션 뒤에 회원 조회 트랜잭션이 하나 더 있는 가장 가벼운 인증 API라,
// 요청 비용 중 ID 변환 몫이 가장 크다(상한). 단계(STEPS)를 올리며 처리량·응답시간과 HikariCP 대기를 함께 기록한다.
// drop-list-scale/concurrency.sh가 SCRIPT로 이 파일을 실행한다. 시나리오 이름(vu<N>)·K6_SCENARIO_START 출력 규약은
// drop-list-scale/concurrency.js와 같다. 사용자는 한 번 조회한 뒤 THINK_S초 쉰다(VU ≈ 동시 접속자).
//
// 기본은 공통 계정 k6-buyer 하나로 setup()에서 한 번 로그인하고 모든 VU가 그 토큰을 쓴다(GR-201 M00-02).
// ACCOUNTS=<수>(2~800)를 주면 accounts-seed.sql의 k6-idlookup-001부터 그 수만큼 로그인하고 VU마다 다른 계정을 쓴다(GR-211).
// 회원 ID 변환 캐시를 넣은 뒤에는 계정 1개면 모든 요청이 캐시에 적중해 효과가 부풀려지기 때문이다. 최대 VU 이상으로 두면
// 한 단계 안에서 VU끼리 계정이 겹치지 않는다. 로그인은 Argon2 동시 실행 상한(grab.password-hash.max-concurrent 4, GR-178)에
// 맞춰 4건씩 묶어 보내며, 800개면 준비에 수십 초~몇 분이 걸린다. 걸린 시간은 K6_SETUP_S로 출력한다.
// Access Token TTL이 15분이라 한 번의 실행은 그 안에 끝나야 한다. 단계 합이 14분을 넘으면 시작하지 않고,
// 로그인 준비 시간까지 더해 14분을 넘으면 setup()에서 멈춘다.
// 만료 등으로 401이 나오면 me_unauthorized_total로 따로 센다. 1건이라도 있으면 임계값 실패로 k6가 0이 아닌 종료 코드를
// 내고, concurrency.sh(set -e)도 멈춘다. 인증되지 않은 요청이 기준선에 섞이지 않게 하기 위해서다(실패율은 소수점 네 자리라
// 적은 수의 401은 0.0000으로 보일 수 있다).
//
// 사전 준비: compose.yaml + compose.loadtest-micro.yaml + compose.loadtest-db-nano.yaml 기동, 공통 계정 시드. 아래 명령은 Backend에서 실행한다.
//   STEPS=25,50,100,200,400,800 SCRIPT=user-id-lookup/concurrency.js OUT_BASE=k6/user-id-lookup/out \
//     k6/drop-list-scale/concurrency.sh <이름> me
// 워밍업: 측정 조건마다 concurrency.sh 직전에 같은 요청을 VU 25로 60초 보내 JVM을 데운다(GR-181 warmup.js와 같은 이유).
// 따로 스크립트를 두지 않고 이 파일을 한 단계로 실행하며 결과는 버린다. 로그인 실패 등 스크립트 오류만 종료 코드로 확인한다.
//   (Backend/k6에서) k6 run --quiet -e STEPS=25 -e STEP_S=60 user-id-lookup/concurrency.js > /dev/null 2>&1 || echo "워밍업 실패"
// 여러 계정으로 잴 때(GR-211)는 accounts-seed.sql을 넣고 워밍업에 -e ACCOUNTS=25(워밍업 VU 수), 측정에 ACCOUNTS=800을 준다.
// concurrency.sh가 ACCOUNTS를 k6 컨테이너에 넘긴다.
// Windows Git Bash에서는 앞에 MSYS_NO_PATHCONV=1을 붙인다. 붙이지 않으면 concurrency.sh의 docker run -w /k6가
// Git 설치 경로(C:/Program Files/Git/k6)로 바뀌어 k6가 실행되지 않는다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, login, primeCookies } from '../common/lib/auth.js';

const TARGET = __ENV.TARGET || 'me';
const STEPS_RAW = (__ENV.STEPS || '25,50,100,200,400,800').split(',');
const STEPS = STEPS_RAW.map(Number);
const STEP_S = Number(__ENV.STEP_S || 60);
const GAP_S = Number(__ENV.GAP_S || 10);
const THINK_S = Number(__ENV.THINK_S || 1);
const EMAIL = __ENV.EMAIL || 'k6-buyer@loadtest.local';
const PASSWORD = __ENV.PASSWORD || 'LoadTest1234!';

// 계정 수. 1이면 EMAIL 하나(GR-201 기준선과 같은 조건), 2 이상이면 k6-idlookup-001부터 그 수만큼 쓴다.
// accounts-seed.sql이 800개를 만들므로 그보다 크면 시작하지 않는다.
const ACCOUNTS_RAW = (__ENV.ACCOUNTS || '1').trim();
const SEEDED_ACCOUNTS = 800;
if (!/^[1-9][0-9]*$/.test(ACCOUNTS_RAW) || Number(ACCOUNTS_RAW) > SEEDED_ACCOUNTS) {
    throw new Error(`ACCOUNTS는 1~${SEEDED_ACCOUNTS}의 정수여야 한다: ${__ENV.ACCOUNTS}`);
}
const ACCOUNTS = Number(ACCOUNTS_RAW);
// 한 번에 보내는 로그인 수. 서버의 Argon2 동시 실행 상한(4)보다 많이 보내면 대기 끝에 거절(429)될 수 있다.
const LOGIN_BATCH = 4;

// 대상은 내 정보 조회 하나다(GR-201 M00-01). concurrency.sh가 대상 이름을 TARGET으로 넘기므로 이름만 확인한다.
const TARGETS = { me: '/api/v1/users/me' };
if (!TARGETS[TARGET]) {
    throw new Error(`알 수 없는 TARGET: ${TARGET}`);
}

// 단계 이름이 vu<N>이라 같은 VU가 두 번 있으면 뒤 단계가 앞 단계를 덮어쓰고, concurrency.sh는 두 단계에 같은 지표를 적는다.
// 1 이상의 정수가 아니거나 중복이면 시작하지 않는다.
const invalidSteps = STEPS_RAW.filter((s, i) => !/^[1-9][0-9]*$/.test(s.trim()) || STEPS.indexOf(STEPS[i]) !== i);
if (invalidSteps.length > 0) {
    throw new Error(`STEPS는 서로 다른 1 이상의 정수여야 한다: ${__ENV.STEPS} (문제 값: ${invalidSteps.join(',')})`);
}

// 마지막 단계가 끝나는 시각. 토큰 만료(15분)와 로그인·종료 여유를 두고 14분까지만 허용한다.
const TOTAL_S = STEPS.length * STEP_S + (STEPS.length - 1) * GAP_S;
if (TOTAL_S > 14 * 60) {
    throw new Error(`단계 합 ${TOTAL_S}초가 Access Token TTL(15분) 안에 끝나지 않는다. STEPS·STEP_S를 줄이거나 나눠 실행한다`);
}

const unauthorized = new Counter('me_unauthorized_total');

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
thresholds.me_unauthorized_total = ['count<1'];

export const options = {
    // 계정 800개 로그인이 기본 제한(60초)을 넘을 수 있다. 토큰 만료는 setup()에서 따로 확인한다.
    setupTimeout: '10m',
    scenarios,
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function idLookupEmail(i) {
    return `k6-idlookup-${String(i).padStart(3, '0')}@loadtest.local`;
}

// 두 번째 계정부터는 login()을 다시 부르지 않는다. 쿠키 저장소에 XSRF-TOKEN이 이미 있으면 /auth/csrf가 새 쿠키를 주지 않아
// login()의 검사가 실패한다(common/lib/auth.js 주석). 첫 로그인에서 받은 CSRF 토큰을 재사용하고, 요청마다 새 쿠키 저장소를 써서
// 앞 계정의 access_token 쿠키가 실리지 않게 한다.
function loginRest(emails, xsrfToken) {
    const tokens = [];
    for (let i = 0; i < emails.length; i += LOGIN_BATCH) {
        const chunk = emails.slice(i, i + LOGIN_BATCH);
        const responses = http.batch(chunk.map((email) => {
            const jar = new http.CookieJar();
            jar.set(BASE_URL, 'XSRF-TOKEN', xsrfToken, { path: '/' });
            return {
                method: 'POST',
                url: `${BASE_URL}/api/v1/auth/login`,
                body: JSON.stringify({ email, password: PASSWORD }),
                params: { jar, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrfToken } },
            };
        }));
        responses.forEach((res, j) => {
            const cookie = res.cookies['access_token'];
            if (res.status !== 200 || !cookie || !cookie[0]) {
                throw new Error(`로그인 실패: ${chunk[j]} status=${res.status} body=${res.body}`);
            }
            tokens.push(cookie[0].value);
        });
    }
    return tokens;
}

// 로그인을 먼저 끝내고 시작 시각을 찍는다. 시나리오는 setup()이 끝난 뒤 시작하므로 이 값이 첫 단계 시작에 가깝다.
// login()은 /auth/csrf를 처음 받아야 해서 setup()의 첫 요청이어야 한다(common/lib/auth.js 주석).
export function setup() {
    const startedAt = Date.now();
    const emails = ACCOUNTS === 1 ? [EMAIL] : Array.from({ length: ACCOUNTS }, (_, i) => idLookupEmail(i + 1));
    const first = login(emails[0], PASSWORD);
    const accessTokens = [first.accessToken, ...loginRest(emails.slice(1), first.xsrfToken)];

    // 첫 토큰은 로그인을 시작할 때 발급됐으므로, 준비 시간까지 더해 마지막 단계가 14분 안에 끝나야 한다.
    const setupS = (Date.now() - startedAt) / 1000;
    console.log(`K6_SETUP_S=${setupS.toFixed(1)} K6_ACCOUNTS=${accessTokens.length}`);
    if (setupS + TOTAL_S > 14 * 60) {
        throw new Error(`로그인 준비 ${setupS.toFixed(0)}초 + 단계 합 ${TOTAL_S}초가 Access Token TTL(15분) 안에 끝나지 않는다`);
    }
    // 401이 한 번도 없어도 요약에 0이 남도록 기록한다.
    unauthorized.add(0);
    console.log(`K6_SCENARIO_START=${Math.floor(Date.now() / 1000)}`);
    return { xsrfToken: first.xsrfToken, accessTokens };
}

// concurrency.sh가 exec 이름을 가정하지 않지만 drop-list-scale과 맞춰 list로 둔다.
export function list(data) {
    // VU 번호(1부터)로 계정을 고른다. 단계가 겹치지 않아 한 단계의 VU 번호는 서로 달라, 계정 수가 VU 수 이상이면 계정도 겹치지 않는다.
    const accessToken = data.accessTokens[(__VU - 1) % data.accessTokens.length];
    // k6는 반복마다 VU 쿠키 저장소를 비우므로 매 반복 토큰을 다시 심는다(다른 시나리오와 같음). 요청은 보내지 않는다.
    primeCookies({ accessToken, xsrfToken: data.xsrfToken });
    const res = http.get(`${BASE_URL}${TARGETS[TARGET]}`, { tags: { target: TARGET } });
    if (res.status === 401) {
        unauthorized.add(1);
    }
    check(res, { 'status 200': (r) => r.status === 200 });
    sleep(THINK_S);
}
