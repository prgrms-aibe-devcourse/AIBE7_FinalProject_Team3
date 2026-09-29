package org.example.grab.global.config;

import jakarta.validation.constraints.Size;
import org.example.grab.global.validation.SensitiveValue;
import org.example.grab.global.validation.SensitiveValueMaskingValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Validator;

import static org.assertj.core.api.Assertions.assertThat;

/*
    DB 없이 검증·MVC 자동 설정만 띄워, 이 설정이 Boot 기본 검증기를 대체하고 MVC의 @Valid 검증기에도 적용되는지 확인한다.
 */
class ValidationConfigTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withUserConfiguration(ValidationConfig.class);

    record SensitiveRequest(@SensitiveValue @Size(min = 100) String password) {
    }

    @Test
    @DisplayName("Boot 기본 검증기 대신 민감 값 마스킹 검증기 하나만 등록된다")
    void replacesDefaultValidator() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(jakarta.validation.Validator.class)
                .getBean(jakarta.validation.Validator.class)
                .isInstanceOf(SensitiveValueMaskingValidator.class));
    }

    @Test
    @DisplayName("MVC의 @Valid 검증기도 민감 필드의 거부된 값을 가린다")
    void appliesToMvcValidator() {
        contextRunner.run(context -> {
            Validator mvcValidator = context.getBean("mvcValidator", Validator.class);
            SensitiveRequest request = new SensitiveRequest("SecretPw1!");
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(request, "request");

            mvcValidator.validate(request, result);

            assertThat(result.getFieldError("password").getRejectedValue())
                    .isEqualTo(SensitiveValueMaskingValidator.MASKED_VALUE);
        });
    }
}
