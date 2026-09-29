package org.example.grab.global.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.lang.reflect.Field;

/*
    @SensitiveValue 필드의 검증 위반을 FieldError로 옮길 때 거부된 값을 고정 문자열로 바꾼다.
    Spring은 DEBUG 로그에 MethodArgumentNotValidException을 "rejected value [...]"와 함께 남기므로,
    로그 레벨 설정에 기대지 않고 값이 예외에 들어가는 지점에서 막는다.
    응답은 rejectedValue를 쓰지 않고, 오류 코드 선택은 FieldError 안의 ConstraintViolation을 쓰므로 영향이 없다.
 */
public class SensitiveValueMaskingValidator extends LocalValidatorFactoryBean {

    public static final String MASKED_VALUE = "masked";

    @Override
    protected Object getRejectedValue(String field, ConstraintViolation<Object> violation, BindingResult bindingResult) {
        if (isSensitive(violation)) {
            return MASKED_VALUE;
        }
        return super.getRejectedValue(field, violation, bindingResult);
    }

    // 중첩 객체(address.password)도 가리도록, 위반한 값을 직접 가진 객체(leafBean)에서 필드를 찾는다
    private boolean isSensitive(ConstraintViolation<Object> violation) {
        Object leafBean = violation.getLeafBean();
        String propertyName = lastPropertyName(violation.getPropertyPath());
        if (leafBean == null || propertyName == null) {
            return false;
        }
        Field field = findField(leafBean.getClass(), propertyName);
        return field != null && field.isAnnotationPresent(SensitiveValue.class);
    }

    // 컨테이너 요소 제약(List<@NotBlank String> tags)은 마지막 노드가 요소이므로, 그 요소를 가진 속성의 이름을 쓴다
    private String lastPropertyName(Path propertyPath) {
        String propertyName = null;
        for (Path.Node node : propertyPath) {
            if (node.getKind() == ElementKind.PROPERTY) {
                propertyName = node.getName();
            }
        }
        return propertyName;
    }

    private Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // 상위 클래스에 선언된 필드일 수 있으므로 계속 찾는다
            }
        }
        return null;
    }
}
