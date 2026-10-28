// GR-201: 인증 요청의 회원 ID 변환(public_id → users.id) 비용을 동시 접속 단계별로 본다.
// 내 정보 조회(GET /api/v1/users/me)는 ID 변환 트랜잭션 뒤에 회원 조회 트랜잭션이 하나 더 있는 가장 가벼운 인증 API라,
// 요청 비용 중 ID 변환 몫이 가장 크다(상한). 단계(STEPS)를 올리며 처리량·응답시간과 HikariCP 대기를 함께 기록한다.
// drop-list-scale/concurrency.sh가 SCRIPT로 이 파일을 실행한다. 시나리오 이름(vu<N>)·K6_SCENARIO_START 출력 규약은
// drop-list-scale/concurrency.js와 같다. 사용자는 한 번 조회한 뒤 THINK_S초 쉰다(VU ≈ 동시 접속자).
//
// 공통 계정 k6-buyer 하나로 setup()에서 한 번 로그인하고 모든 VU가 그 토큰을 쓴다(GR-201 M00-02).
// Access Token TTL이 15분이라 한 번의 실행은 그 안에 끝나야 한다. 단계 합이 14분을 넘으면 시작하지 않는다.
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
    scenarios,
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 로그인을 먼저 끝내고 시작 시각을 찍는다. 시나리오는 setup()이 끝난 뒤 시작하므로 이 값이 첫 단계 시작에 가깝다.
// login()은 /auth/csrf를 처음 받아야 해서 setup()의 첫 요청이어야 한다(common/lib/auth.js 주석).
export function setup() {
    const session = login(EMAIL, PASSWORD);
    // 401이 한 번도 없어도 요약에 0이 남도록 기록한다.
    unauthorized.add(0);
    console.log(`K6_SCENARIO_START=${Math.floor(Date.now() / 1000)}`);
    return session;
}

// concurrency.sh가 exec 이름을 가정하지 않지만 drop-list-scale과 맞춰 list로 둔다.
export function list(session) {
    // k6는 반복마다 VU 쿠키 저장소를 비우므로 매 반복 토큰을 다시 심는다(다른 시나리오와 같음). 요청은 보내지 않는다.
    primeCookies(session);
    const res = http.get(`${BASE_URL}${TARGETS[TARGET]}`, { tags: { target: TARGET } });
    if (res.status === 401) {
        unauthorized.add(1);
    }
    check(res, { 'status 200': (r) => r.status === 200 });
    sleep(THINK_S);
}
