package org.example.grab.domain.user.error;

import org.example.grab.domain.user.validation.ValidEmail;
import org.example.grab.domain.user.validation.ValidNickname;
import org.example.grab.domain.user.validation.ValidPassword;
import org.example.grab.global.error.ConstraintErrorCodeMapping;
import org.example.grab.global.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.Map;

/*
    user 도메인의 검증 제약을 오류 코드로 연결한다(MEMBER_AUTH.md 1.2, 1.2.3).
    필수값 위반(@NotEmpty)은 등록하지 않아 VALIDATION_FAILED로 응답한다.
    표준 제약(@Size·@Pattern 등)을 등록하면 다른 DTO의 같은 제약까지 이 코드로 바뀌므로, user 전용 제약만 등록한다.
 */
@Component
public class UserConstraintErrorCodeMapping implements ConstraintErrorCodeMapping {

    @Override
    public Map<Class<? extends Annotation>, ErrorCode> errorCodes() {
        return Map.of(
                ValidEmail.class, UserErrorCode.INVALID_EMAIL,
                ValidPassword.class, UserErrorCode.INVALID_PASSWORD,
                ValidNickname.class, UserErrorCode.INVALID_NICKNAME
        );
    }
}
