package org.example.grab.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

// @Async는 애플리케이션 전체에 적용되므로 특정 도메인 설정에 두지 않는다. 작업별 executor는 각 도메인 설정이 등록한다
@Configuration
@EnableAsync
public class AsyncConfig {
}
