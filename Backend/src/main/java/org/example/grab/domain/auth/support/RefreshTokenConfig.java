package org.example.grab.domain.auth.support;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// global은 도메인을 참조하지 않으므로 SecurityConfig가 아니라 auth 도메인 안에서 설정을 등록한다
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefreshTokenProperties.class)
public class RefreshTokenConfig {

}
