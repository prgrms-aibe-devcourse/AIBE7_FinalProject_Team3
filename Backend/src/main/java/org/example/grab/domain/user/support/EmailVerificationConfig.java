package org.example.grab.domain.user.support;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// global은 도메인을 참조하지 않으므로 user 도메인 안에서 설정을 등록한다(RefreshTokenConfig와 같은 방식)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EmailVerificationProperties.class, EmailVerificationSendLimitProperties.class})
public class EmailVerificationConfig {

}
