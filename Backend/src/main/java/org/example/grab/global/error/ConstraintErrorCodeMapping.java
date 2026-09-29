package org.example.grab.global.error;

import java.lang.annotation.Annotation;
import java.util.Map;

/*
    검증 제약 애노테이션을 도메인 오류 코드로 연결하는 매핑이다.
    global은 특정 도메인을 참조하지 않으므로(CODING_CONVENTION.md 2.1), 각 도메인이 이 인터페이스를 구현한 빈을 등록한다.
    등록되지 않은 제약의 위반은 VALIDATION_FAILED로 응답한다.
 */
public interface ConstraintErrorCodeMapping {

    Map<Class<? extends Annotation>, ErrorCode> errorCodes();
}
