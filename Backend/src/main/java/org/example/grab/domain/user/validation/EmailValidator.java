package org.example.grab.domain.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/*
    한 필드에 사유를 하나만 보고해야 하므로(MEMBER_AUTH.md 1.2) 길이 → 형식(중간 공백 포함) 순서로 검사하고 처음 실패한 단계에서 멈춘다.
    정규화(앞뒤 공백 제거·소문자 변환)는 요청 DTO 생성자가 담당하므로 여기서는 값을 가공하지 않는다.
 */
public class EmailValidator implements ConstraintValidator<ValidEmail, String> {

    // RFC 5321상 경로 길이 제한에 따라 전체 주소는 254자를 넘을 수 없다. users.email도 VARCHAR(254)다
    static final int MAX_LENGTH = 254;

    static final String LENGTH_MESSAGE = "이메일은 254자 이하여야 합니다.";
    static final String WHITESPACE_MESSAGE = "이메일에 공백을 포함할 수 없습니다.";
    static final String FORMAT_MESSAGE = "올바른 이메일 형식이 아닙니다.";

    /*
        로컬 부분 1자 이상 + @ 하나 + 점으로 구분한 도메인 라벨 2개 이상만 확인한다.
        실제로 받을 수 있는 주소인지는 인증 코드 메일로 확인한다.
     */
    private static final Pattern FORMAT = Pattern.compile("[^@]+@[^@.]+(\\.[^@.]+)+");

    @Override
    public boolean isValid(String email, ConstraintValidatorContext context) {
        // 필수값 위반은 @NotEmpty가 보고한다. ""까지 형식 사유로 보고하면 한 필드에 위반이 두 개 생긴다
        if (email == null || email.isEmpty()) {
            return true;
        }
        // DB VARCHAR(254)는 문자 수로 세므로 코드 포인트로 센다
        if (email.codePointCount(0, email.length()) > MAX_LENGTH) {
            return reject(context, LENGTH_MESSAGE);
        }
        // 앞뒤 공백은 정규화에서 제거되므로 여기서 걸리는 것은 중간 공백이다. 비밀번호 검증과 같은 공백 기준을 쓴다
        if (email.chars().anyMatch(Character::isWhitespace)) {
            return reject(context, WHITESPACE_MESSAGE);
        }
        if (!FORMAT.matcher(email).matches()) {
            return reject(context, FORMAT_MESSAGE);
        }

        return true;
    }

    // 기본 위반을 끄지 않으면 애노테이션 기본 문구와 이 사유가 함께 보고돼 한 필드에 사유가 두 개가 된다
    private boolean reject(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
