package org.example.grab;

import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/*
    application.yml의 pgjdbc logServerErrorDetail=false 확인(GR-29, 팀 결정).
    처리하지 않은 DB 오류는 GlobalExceptionHandler가 ERROR 로그에 스택 트레이스로 남기므로,
    예외 메시지에 PostgreSQL 오류 상세(Failing row contains ...)로 행 값이 들어가면 안 된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseErrorDetailTests {

    private static final String EMAIL = "detail-probe@example.com";
    private static final String PASSWORD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$ZGV0YWls$cHJvYmU";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("제약 위반 예외 메시지에는 제약 이름만 남고 행 값(이메일·비밀번호 해시)은 없다")
    void omitsServerErrorDetailFromExceptionMessage() {
        // when: CHECK 위반. 값은 바인딩해 SQL 문장에는 들어가지 않으므로, 남는다면 서버 오류 상세에서 온 것이다
        DataIntegrityViolationException exception = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO users (email, password_hash, nickname, role) VALUES (?, ?, 'detail', 'NOT_A_ROLE')",
                        EMAIL, PASSWORD_HASH));

        // then
        String messages = String.join(" | ", messagesOf(exception));
        assertThat(messages).contains("ck_users_role")
                .doesNotContain(EMAIL)
                .doesNotContain(PASSWORD_HASH);
    }

    private static List<String> messagesOf(Throwable throwable) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return messages;
    }
}
