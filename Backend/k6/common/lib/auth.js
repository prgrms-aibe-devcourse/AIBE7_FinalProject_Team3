import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// GR-150 제약: 카카오/구글 로그인은 쓰지 않는다(소셜 로그인을 VU마다 반복 호출하면
// 외부 서비스에 비정상 트래픽으로 잡혀 앱 키가 제재될 수 있다). 자체 이메일/비밀번호
// 로그인만 쓰고, 토큰은 setup()에서 한 번 발급해 모든 VU가 재사용한다.
export function login(email, password) {
    const csrfRes = http.get(`${BASE_URL}/api/v1/auth/csrf`);
    check(csrfRes, { 'csrf 발급 204': (r) => r.status === 204 });
    if (csrfRes.status !== 204) {
        throw new Error(`CSRF 발급 실패: status=${csrfRes.status} body=${csrfRes.body}`);
    }
    const xsrfCookie = csrfRes.cookies['XSRF-TOKEN'];
    if (!xsrfCookie || !xsrfCookie[0]) {
        throw new Error('CSRF 응답에 XSRF-TOKEN 쿠키가 없습니다.');
    }
    const xsrfToken = xsrfCookie[0].value;

    // 서버는 XSRF-TOKEN 쿠키에 Secure 속성을 강제로 붙인다(SecurityConfig.csrfTokenRepository).
    // k6의 쿠키 저장소는 Secure 쿠키를 http:// 요청엔 자동으로 다시 실어 보내지 않으므로,
    // 로컬 HTTP 부하테스트에서는 값만 가져와 Secure 없이 다시 심어서 쓴다.
    http.cookieJar().set(BASE_URL, 'XSRF-TOKEN', xsrfToken, { path: '/' });

    const loginRes = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email, password }),
        { headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrfToken } }
    );
    check(loginRes, { '로그인 200': (r) => r.status === 200 });
    if (loginRes.status !== 200) {
        throw new Error(`로그인 실패: status=${loginRes.status} body=${loginRes.body}`);
    }
    const accessTokenCookie = loginRes.cookies['access_token'];
    if (!accessTokenCookie || !accessTokenCookie[0]) {
        throw new Error('로그인 응답에 access_token 쿠키가 없습니다.');
    }

    return {
        accessToken: accessTokenCookie[0].value,
        xsrfToken,
    };
}

// k6은 VU마다 쿠키 저장소가 분리돼 있어, setup()에서 받은 토큰을 각 VU 실행 시작 시
// 한 번씩 심어줘야 한다(매 iteration마다 다시 로그인하지 않기 위함).
export function primeCookies(session) {
    const jar = http.cookieJar();
    jar.set(BASE_URL, 'access_token', session.accessToken, { path: '/api' });
    jar.set(BASE_URL, 'XSRF-TOKEN', session.xsrfToken, { path: '/' });
}

export function authHeaders(session, extra = {}) {
    return Object.assign(
        { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': session.xsrfToken },
        extra
    );
}
