package org.example.grab.domain.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/*
    한 필드에 사유를 하나만 보고해야 하므로(MEMBER_AUTH.md 1.2) 길이 → 공백 → 허용 문자 → 문자 조합 순서로 검사하고 처음 실패한 단계에서 멈춘다.
    비밀번호는 읽기만 하고 trim·치환하지 않는다.
 */

// ConstraintValidator는 Bean Validation이 정해 둔 검사기 인터페이스
// @ValidPassword가 붙은 String 값을 검사한다는 의미
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 64;

    static final String LENGTH_MESSAGE = "비밀번호는 8자 이상 64자 이하여야 합니다.";
    static final String WHITESPACE_MESSAGE = "비밀번호에 공백을 포함할 수 없습니다.";
    static final String CHARACTER_SET_MESSAGE = "비밀번호는 영문, 숫자, 특수문자만 사용할 수 있습니다.";
    static final String COMPOSITION_MESSAGE = "비밀번호는 영문, 숫자, 특수문자를 각각 1자 이상 포함해야 합니다.";

    // ASCII 0x21~0x7E 중 영숫자를 제외한 32개
    private static final String SPECIAL_CHARACTERS = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    @Override
    // 길이 → 공백 → 허용 문자 → 문자 조합 순서로 검사하고, 처음 실패한 단계의 사유 하나만 보고
    // ConstraintValidatorContext에는 위반 내용을 전달하기 위한 객체
    public boolean isValid(String password, ConstraintValidatorContext context) {
        // 필수값 위반은 @NotEmpty가 보고한다. ""까지 길이 사유로 보고하면 한 필드에 위반이 두 개 생긴다
        if (password == null || password.isEmpty()) {
            return true;
        }
        // 허용 문자는 모두 ASCII이므로 char 수를 길이로 본다
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return reject(context, LENGTH_MESSAGE);
        }
        if (containsWhitespace(password)) {
            return reject(context, WHITESPACE_MESSAGE);
        }
        if (!containsOnlyAllowedCharacters(password)) {
            return reject(context, CHARACTER_SET_MESSAGE);
        }
        if (!containsAsciiLetter(password) || !containsAsciiDigit(password) || !containsSpecialCharacter(password)) {
            return reject(context, COMPOSITION_MESSAGE);
        }

        return true;
    }

    // String.strip()·isBlank()와 같은 기준을 쓰기 위해 isSpaceChar는 함께 쓰지 않는다
    private boolean containsWhitespace(String password) {
        // 문자열 하나하나를 확인하며 isWhiteSpace 를 통해 공백이 있는지 체크
        return password.chars().anyMatch(Character::isWhitespace);
    }

    /*
        유니코드 정규화(NFC/NFD)·IME 차이로 같은 모양의 비밀번호가 다르게 저장돼 로그인에 실패하지 않도록 ASCII 0x21~0x7E만 허용한다.
        공백(0x20)은 앞 단계에서 걸러지므로 이 범위는 영문·숫자·특수문자 32개와 같다.
     */
    private boolean containsOnlyAllowedCharacters(String password) {
        return password.chars().allMatch(c -> c >= 0x21 && c <= 0x7E);
    }

    // Character.isLetter·isDigit은 한글·전각 문자까지 참이므로 ASCII 범위를 직접 비교한다
    // 문자열 중 하나라도 영문이 한글자라도 있는지 검증
    private boolean containsAsciiLetter(String password) {
        return password.chars().anyMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
    }

    // 숫자가 하나라도 있는지 검증
    private boolean containsAsciiDigit(String password) {
        return password.chars().anyMatch(c -> c >= '0' && c <= '9');
    }

    // 허용 특수문자가 비밀번호 문자열에 하나 이상 포함되는지 검증
    private boolean containsSpecialCharacter(String password) {
        return password.chars().anyMatch(c -> SPECIAL_CHARACTERS.indexOf(c) >= 0);
    }

    // Bean Validation 엔진은 isValid가 false일 때만 context에 쌓인 사유를 위반으로 만든다.
    // 기본 위반을 끄지 않으면 애노테이션 기본 문구와 이 사유가 함께 보고돼 한 필드에 사유가 두 개가 된다.
    private boolean reject(ConstraintValidatorContext context, String message) {
        // 기본 위반 문구 비활성화
        context.disableDefaultConstraintViolation();
        // message를 위반 내용으로 작성
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
