package org.example.grab.global.error;

import jakarta.validation.ConstraintViolation;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
    필드 검증 오류 하나가 어떤 오류 코드에 해당하는지 정한다.
    한글 문구가 아니라 위반한 제약 애노테이션의 타입으로 구분하므로, 문구를 바꿔도 오류 코드가 흔들리지 않는다(GR-28 M06-05).
 */
@Component
public class ValidationErrorCodeResolver {

    private final Map<Class<? extends Annotation>, ErrorCode> errorCodes;

    public ValidationErrorCodeResolver(List<ConstraintErrorCodeMapping> mappings) {
        Map<Class<? extends Annotation>, ErrorCode> merged = new HashMap<>();
        for (ConstraintErrorCodeMapping mapping : mappings) {
            mapping.errorCodes().forEach((constraint, errorCode) -> {
                // 두 도메인이 같은 제약에 서로 다른 코드를 등록하면 응답 코드가 등록 순서에 따라 달라지므로 기동 시 실패시킨다
                ErrorCode previous = merged.putIfAbsent(constraint, errorCode);
                if (previous != null && previous != errorCode) {
                    throw new IllegalStateException("제약 " + constraint.getName() + "에 오류 코드가 중복 등록되었습니다: "
                            + previous.getCode() + ", " + errorCode.getCode());
                }
            });
        }
        this.errorCodes = Map.copyOf(merged);
    }

    public ErrorCode resolve(FieldError fieldError) {
        // 타입 변환 실패처럼 Bean Validation 위반이 아닌 필드 오류는 제약 정보가 없으므로 기본 코드로 둔다
        if (!fieldError.contains(ConstraintViolation.class)) {
            return CommonErrorCode.VALIDATION_FAILED;
        }
        Class<? extends Annotation> constraint = fieldError.unwrap(ConstraintViolation.class)
                .getConstraintDescriptor()
                .getAnnotation()
                .annotationType();
        return errorCodes.getOrDefault(constraint, CommonErrorCode.VALIDATION_FAILED);
    }
}
