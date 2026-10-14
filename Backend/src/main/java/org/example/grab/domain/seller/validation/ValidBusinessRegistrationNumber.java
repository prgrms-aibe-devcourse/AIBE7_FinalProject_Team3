package org.example.grab.domain.seller.validation;

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

// 하이픈 없는 10자리 숫자(SELLER-001, api-spec/SELLER.md 1.2절). 필수값 위반은 검사하지 않으므로 @NotEmpty와 함께 쓴다.
@Documented
@Constraint(validatedBy = BusinessRegistrationNumberValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface ValidBusinessRegistrationNumber {

    String message() default "사업자등록번호는 하이픈 없는 10자리 숫자여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
