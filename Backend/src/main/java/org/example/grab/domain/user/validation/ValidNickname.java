package org.example.grab.domain.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/*
    MEMBER_AUTH.md 1.2.3 닉네임 규칙(길이·허용 문자)을 하나의 제약으로 묶는다.
    필수값은 검사하지 않으므로 @NotEmpty와 함께 사용하고, 앞뒤 공백은 요청 DTO 생성자에서 제거한 값을 받는다.
    오류 코드 변환에서 이 제약 타입을 INVALID_NICKNAME의 식별 정보로 사용하므로, 다른 DTO의 @Size·@Pattern과 섞이지 않는다.
    소셜 회원가입(1.6)·내 정보 수정(1.10)에서도 같은 규칙을 쓴다.
 */
@Documented
@Constraint(validatedBy = NicknameValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface ValidNickname {

    String message() default "닉네임이 규칙을 충족하지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
