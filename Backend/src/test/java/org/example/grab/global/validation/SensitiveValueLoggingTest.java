package org.example.grab.global.validation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    Spring은 DEBUG 레벨에서 역직렬화한 요청 DTO와 검증 예외(rejected value 포함)를 로그에 남긴다.
    로그 레벨을 DEBUG로 올려도 @SensitiveValue 필드의 원문이 어느 로그에도 남지 않는지 확인한다(GR-28 M07-07).
 */
class SensitiveValueLoggingTest {

    private static final String SECRET = "SecretPw1!";

    private final Logger springLogger = (Logger) LoggerFactory.getLogger("org.springframework");
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    private Level previousSpringLevel;

    private SensitiveValueMaskingValidator validator;
    private MockMvc mockMvc;

    @RestController
    static class TestController {

        @PostMapping("/test/sensitive")
        void sensitive(@Valid @RequestBody SensitiveRequest request) {
        }
    }

    // DTO 전체를 출력하는 DEBUG 로그도 있으므로, 민감 필드를 가진 DTO는 toString()에서도 값을 가린다
    record SensitiveRequest(@SensitiveValue @Size(min = 100) String password) {

        @Override
        public String toString() {
            return "SensitiveRequest[password=masked]";
        }
    }

    @BeforeEach
    void setUp() {
        validator = new SensitiveValueMaskingValidator();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .setValidator(validator)
                .build();

        previousSpringLevel = springLogger.getLevel();
        springLogger.setLevel(Level.DEBUG);
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
        springLogger.setLevel(previousSpringLevel);
        validator.close();
    }

    @Test
    @DisplayName("DEBUG 로그에도 검증에 실패한 민감 필드의 원문이 남지 않는다")
    void doesNotLogSensitiveValueAtDebugLevel() throws Exception {
        // when
        mockMvc.perform(post("/test/sensitive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + SECRET + "\"}"))
                .andExpect(status().isBadRequest());

        // then
        // 검증 예외를 기록하는 Spring 로그가 실제로 남았는지 함께 확인해, 로그가 없어서 통과하는 경우를 막는다
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("MethodArgumentNotValidException"))
                .noneMatch(message -> message.contains(SECRET));
    }
}
