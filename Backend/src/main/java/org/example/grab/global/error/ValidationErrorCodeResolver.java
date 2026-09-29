package org.example.grab.global.error;

import jakarta.validation.ConstraintViolation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
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
                // 요청 검증 실패는 HTTP 400으로 응답해야 하므로(COMMON.md 1.6), 다른 상태의 코드를 매핑하면 상태와 코드가 어긋난 응답이 나간다
                if (errorCode.getStatus() != HttpStatus.BAD_REQUEST) {
                    throw new IllegalStateException("제약 " + constraint.getName() + "에는 HTTP 400 오류 코드만 등록할 수 있습니다: "
                            + errorCode.getCode() + "(" + errorCode.getStatus().value() + ")");
                }
                // 두 도메인이 같은 제약에 서로 다른 코드를 등록하면 응답 코드가 등록 순서에 따라 달라지므로 기동 시 실패시킨다
                // putIfAbsent(키, 값)은 이 키가 merged 맵에 없을 때만 넣음
                ErrorCode previous = merged.putIfAbsent(constraint, errorCode);
                // 값이 있는데 동일 constraint에 다른 errorCode가 있으면 중복 등록 오류 발생
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
        // 검증에 에러가 발생한 어노테이션의 클래스 정보를 추출 ex) ValidNickname.class
        Class<? extends Annotation> constraint = fieldError.unwrap(ConstraintViolation.class)
                .getConstraintDescriptor()
                .getAnnotation()
                .annotationType();
        return errorCodes.getOrDefault(constraint, CommonErrorCode.VALIDATION_FAILED);
    }

    /*
        요청 하나의 검증 실패 전체를 최상위 코드와 정렬된 필드 오류 목록으로 정리한다(MEMBER_AUTH.md 1.2.3 "여러 항목이 동시에 검증에 실패한 경우").
        필드당 사유 하나만 남기는 일은 각 DTO의 제약 조합이 맡으므로(필수값 제약과 규칙 제약이 같은 값을 함께 거부하지 않게 조합), 여기서는 받은 필드 오류를 모두 담는다.
     */
    public ValidationFailure resolve(BindingResult bindingResult) {
        List<FieldError> fieldErrors = sortByDeclarationOrder(bindingResult);
        return new ValidationFailure(selectErrorCode(bindingResult, fieldErrors), fieldErrors);
    }

    /*
        Bean Validation이 위반을 보고하는 순서는 보장되지 않으므로, 요청 DTO에 필드를 선언한 순서로 정렬한다.
        명세의 fieldErrors 순서(password → nickname)는 DTO 선언 순서와 같게 유지한다.
        선언에서 찾지 못한 필드는 뒤로 보내고, 같은 필드끼리는 받은 순서를 유지한다.
     */
    private List<FieldError> sortByDeclarationOrder(BindingResult bindingResult) {
        List<String> declaredFields = declaredFieldNames(bindingResult.getTarget());
        return bindingResult.getFieldErrors().stream()
                .sorted(Comparator.comparingInt(error -> declarationIndex(declaredFields, error.getField())))
                .toList();
    }

    private List<String> declaredFieldNames(Object target) {
        if (target == null) {
            return List.of();
        }
        Class<?> type = target.getClass();
        // record 구성 요소는 선언 순서로 반환되는 것이 보장된다
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
        }
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    private int declarationIndex(List<String> declaredFields, String field) {
        // 중첩 필드(address.city, options[0].name)는 최상위 필드의 위치를 따른다
        String rootField = field.split("[.\\[]", 2)[0];
        int index = declaredFields.indexOf(rootField);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /*
        필드 오류들의 코드가 모두 같으면 그 코드를, 서로 다르면 VALIDATION_FAILED를 쓴다.
        클래스 단위 제약 위반은 fieldErrors에 담기지 않으므로, 있으면 특정 필드의 코드로 대표하지 않고 VALIDATION_FAILED로 둔다.
     */
    private ErrorCode selectErrorCode(BindingResult bindingResult, List<FieldError> fieldErrors) {
        if (bindingResult.hasGlobalErrors()) {
            return CommonErrorCode.VALIDATION_FAILED;
        }
        List<ErrorCode> fieldErrorCodes = fieldErrors.stream()
                .map(this::resolve)
                .distinct()
                .toList();
        return fieldErrorCodes.size() == 1 ? fieldErrorCodes.get(0) : CommonErrorCode.VALIDATION_FAILED;
    }
}
