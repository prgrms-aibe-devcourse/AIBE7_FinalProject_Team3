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
    MEMBER_AUTH.md 1.2 비밀번호 규칙을 하나의 제약으로 묶는다.
    필수값은 검사하지 않으므로 @NotEmpty와 함께 사용한다.
    오류 코드 변환에서 이 제약 타입을 INVALID_PASSWORD의 식별 정보로 사용하므로, 다른 DTO의 @Size·@Pattern과 섞이지 않는다.
 */
@Documented
// @ValidPassword 어노테이션이 붙은 값은 PasswordValidator가 검사함
@Constraint(validatedBy = PasswordValidator.class)
// 이 어노테이션을 붙일 수 있는 곳 정의 -> 메서드, 필드, 어노테이션 선언, 매개 변수, 타입을 사용하는 곳 ex) List<@ValidPassword String> password
@Target({METHOD, FIELD, ANNOTATION_TYPE, PARAMETER, TYPE_USE})
// 프로그램이 실행되는 동안에 어노테이션 정보를 찾아볼 수 있도록..
@Retention(RUNTIME)
// @interface는 어노테이션 타입을 선언하는 Java 키워드
public @interface ValidPassword {

    // Bean Validation 스펙 상 모든 제약 어노테이션에 반드시 있어야 하는 세 속성
    String message() default "비밀번호가 규칙을 충족하지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
