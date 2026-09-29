package org.example.grab.domain.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/*
    한 필드에 사유를 하나만 보고해야 하므로(MEMBER_AUTH.md 1.2.3) 길이 → 허용 문자 순서로 검사하고 처음 실패한 단계에서 멈춘다.
    앞뒤 공백 제거는 요청 DTO 생성자가 담당하므로 여기서는 값을 가공하지 않는다.
 */
public class NicknameValidator implements ConstraintValidator<ValidNickname, String> {

    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 10;

    static final String LENGTH_MESSAGE = "닉네임은 2자 이상 10자 이하여야 합니다.";
    static final String CHARACTER_SET_MESSAGE = "닉네임은 한글, 영문, 숫자, 밑줄(_)만 사용할 수 있습니다.";

    @Override
    public boolean isValid(String nickname, ConstraintValidatorContext context) {
        // 필수값 위반은 @NotEmpty가 보고한다. ""까지 길이 사유로 보고하면 한 필드에 위반이 두 개 생긴다
        if (nickname == null || nickname.isEmpty()) {
            return true;
        }
        // 허용 문자는 모두 char 하나라 규칙을 지킨 닉네임은 char 수와 결과가 같다.
        // 이모지 등 허용 외 문자가 섞이면 세는 방식에 따라 길이·허용 문자 중 보고할 사유가 달라지므로, 사용자가 보는 글자 수에 맞춰 명세대로 코드 포인트로 센다
        int length = nickname.codePointCount(0, nickname.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return reject(context, LENGTH_MESSAGE);
        }
        if (!nickname.codePoints().allMatch(NicknameValidator::isAllowedCharacter)) {
            return reject(context, CHARACTER_SET_MESSAGE);
        }

        return true;
    }

    /*
        허용 문자: 완성형 한글(가~힣), 영문(A~Z, a~z), 숫자(0~9), 밑줄(_)

        Character.isLetter·isDigit을 쓰지 않고 문자 범위를 직접 비교한다.
        이 함수들은 영문·숫자만이 아니라 전 세계 글자·숫자에 true를 반환해서, 막아야 할 문자가 통과하기 때문이다.
          - 한글 자모: ㄱ, ㅏ        → isLetter가 true
          - 전각 영문·숫자: Ｇ, １     → isLetter·isDigit이 true
          - 다른 언어: ド, 中, é      → isLetter가 true
        특히 전각 문자를 허용하면 "Ｇrab"처럼 기존 닉네임 "Grab"과 똑같아 보이는 닉네임을 만들 수 있다.
        lower(nickname) 유니크 인덱스는 전각을 반각으로 바꾸지 않으므로 두 값을 다른 닉네임으로 보고 둘 다 저장한다.

        완성형 한글은 유니코드에서 '가'(U+AC00)부터 '힣'(U+D7A3)까지 연속으로 놓여 있고,
        자모 'ㄱ'(U+3131)은 이 범위 밖에 있어 범위 비교만으로 걸러진다.
     */
    private static boolean isAllowedCharacter(int c) {
        return (c >= '가' && c <= '힣')
                || (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }

    private boolean reject(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
