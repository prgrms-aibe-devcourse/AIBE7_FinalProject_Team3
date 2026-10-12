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
    MEMBER_AUTH.md 1.2 이메일 규칙(길이·형식)을 하나의 제약으로 묶는다.
    필수값은 검사하지 않으므로 @NotEmpty와 함께 사용하고, 요청 DTO 생성자에서 EmailNormalizer로 정규화한 값을 받는다.
    오류 코드 변환에서 이 제약 타입을 INVALID_EMAIL의 식별 정보로 사용한다.
    표준 @Email·@Size를 등록하면 다른 DTO의 같은 제약까지 INVALID_EMAIL이 되므로 전용 제약을 둔다(GR-28 M06-01).
 */
@Documented
@Constraint(validatedBy = EmailValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface ValidEmail {

    String message() default "이메일 형식이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
