package org.example.grab.domain.user.support;

import java.util.Locale;

/*
    MEMBER_AUTH.md 1.2 이메일 정규화 규칙을 한 곳에서 적용한다.
    가입·로그인 요청 DTO, 중복 검사·저장, 소셜 제공자 이메일이 모두 이 함수를 사용해야 같은 값으로 비교된다.
    users.email 유니크 제약은 대소문자를 구분하므로, 이 함수로 정규화한 값만 조회·저장해야 대소문자만 다른 중복 가입을 막을 수 있다.
    서비스·Repository에서 toLowerCase()·trim()·IgnoreCase 쿼리로 따로 변환하지 않는다.
    record 생성자에서 호출해야 하므로 Spring 빈이 아닌 정적 함수로 둔다.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /*
        앞뒤 공백을 제거하고 전체를 소문자로 변환한다.
        null은 필수값 검증에서 처리하도록 그대로 반환하고, 중간 공백은 이후 형식 검증에서 거부하도록 남겨 둔다.
        형식·길이는 검사하지 않으며(요청 DTO의 Bean Validation 담당), 여러 번 호출해도 결과가 같다.
     */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
