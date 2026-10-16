package org.example.grab.domain.auth.support.oauth2;

import org.springframework.stereotype.Component;

/*
    콜백 결과를 보낼 프론트엔드 주소를 만든다(MEMBER_AUTH 1.5, GR-46 M00-07).
    프론트엔드는 해시 라우터를 쓰므로 쿼리를 해시 뒤에 붙여야 라우터가 읽는다(예: http://localhost:5173/#/auth/callback?result=success).
    UriComponentsBuilder.queryParam은 쿼리를 # 앞에 붙이므로 쓰지 않고 설정값 뒤에 이어 붙인다.
    붙는 값은 result=success·signup_required, error=<오류 코드>뿐이라 인코딩이 필요 없다. 인가 코드·토큰·이메일은 넣지 않는다.
 */
@Component
public class SocialLoginRedirects {

    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_SIGNUP_REQUIRED = "signup_required";

    private final String callbackUrl;

    public SocialLoginRedirects(SocialLoginProperties properties) {
        this.callbackUrl = properties.frontendCallbackUrl();
    }

    // 로그인 성공 시 기본 주소에 result=success를 붙임
    public String success() {
        return withQuery("result", RESULT_SUCCESS);
    }

    // 소셜 로그인 인증은 성공했지만 GRAB 가입이 필요
    public String signupRequired() {
        return withQuery("result", RESULT_SIGNUP_REQUIRED);
    }

    // errorCode는 서버가 정한 오류 코드(ErrorCode.getCode())만 받는다. 요청에서 온 값을 넘기지 않는다
    public String error(String errorCode) {
        return withQuery("error", errorCode);
    }

    // 공통 url 생성
    private String withQuery(String name, String value) {
        // 해시 뒤에 이미 쿼리가 있으면 &로 잇는다
        int fragment = callbackUrl.indexOf('#');
        String tail = fragment < 0 ? callbackUrl : callbackUrl.substring(fragment);
        // ?가 없으면 첫 번째 쿼리 시작, 있으면 기존 쿼리에 추가
        return callbackUrl + (tail.contains("?") ? "&" : "?") + name + "=" + value;
    }
}
