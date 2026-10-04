package com.campus.tracker.auth.abuse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthAbuseProperties.class)
public class AuthAbuseConfiguration {
    @Bean AuthRateLimiter authAbuseRateLimiter(AuthAbuseProperties properties) {
        return new AuthRateLimiter(properties.maximumKeys(), System::nanoTime);
    }
    @Bean AuthWorkLimiter authWorkLimiter(AuthAbuseProperties properties) {
        return new AuthWorkLimiter(properties.maximumActive());
    }
}
