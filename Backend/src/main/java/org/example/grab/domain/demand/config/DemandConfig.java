package org.example.grab.domain.demand.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DemandSuggestionProperties.class)
public class DemandConfig {
}
