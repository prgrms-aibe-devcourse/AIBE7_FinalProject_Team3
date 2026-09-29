package org.example.grab.global.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/*
    검증에 실패해도 거부된 원문 값을 FieldError에 남기지 않을 필드를 표시한다(GR-28 M07-07).
    FieldError의 rejectedValue는 예외 메시지(MethodArgumentNotValidException)를 거쳐 로그에 남을 수 있으므로,
    비밀번호처럼 원문이 노출되면 안 되는 필드에 붙인다. record 구성 요소에 붙이면 필드로 전파된다.
 */
@Documented
@Target(FIELD)
@Retention(RUNTIME)
public @interface SensitiveValue {
}
