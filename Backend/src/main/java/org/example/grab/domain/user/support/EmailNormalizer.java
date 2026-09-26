package org.example.grab.domain.user.support;

import java.util.Locale;

/*
    MEMBER_AUTH.md 1.2 이메일 정규화 규칙을 한 곳에서 적용한다.
    가입·로그인 요청 DTO, 중복 검사·저장, 소셜 제공자 이메일이 모두 이 함수를 사용해야 같은 값으로 비교된다.
    record 생성자에서 호출해야 하므로 Spring 빈이 아닌 정적 함수로 둔다.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /*
        앞뒤 공백을 제거하고 전체를 소문자로 변환한다.
        null은 필수값 검증에서 처리하도록 그대로 반환하고, 중간 공백은 이후 형식 검증에서 거부하도록 남겨 둔다.
     */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
