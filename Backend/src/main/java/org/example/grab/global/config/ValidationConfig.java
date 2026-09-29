package org.example.grab.global.config;

import org.example.grab.global.validation.SensitiveValueMaskingValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@Configuration
public class ValidationConfig {

    /*
        Boot 기본 검증기(ValidationAutoConfiguration.defaultValidator)를 민감 값 마스킹 검증기로 바꾼다.
        같은 타입의 빈이 있으면 기본 검증기가 생성되지 않으므로, 기본 검증기의 메시지 보간·설정 커스터마이저 적용을 그대로 옮긴다.
        MVC의 @Valid도 컨텍스트의 이 검증기를 사용한다.
     */
    @Bean
    public static LocalValidatorFactoryBean defaultValidator(
            ApplicationContext applicationContext,
            ObjectProvider<ValidationConfigurationCustomizer> customizers
    ) {
        LocalValidatorFactoryBean validator = new SensitiveValueMaskingValidator();
        validator.setConfigurationInitializer(configuration ->
                customizers.orderedStream().forEach(customizer -> customizer.customize(configuration)));
        validator.setMessageInterpolator(new MessageInterpolatorFactory(applicationContext).getObject());
        return validator;
    }
}
