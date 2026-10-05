package com.campus.tracker.auth.abuse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@EnableConfigurationProperties(AuthAbuseProperties.class)
public class AuthAbuseConfiguration {
    @Bean @Profile("!distributed") AuthAdmissionLimiter authAbuseRateLimiter(AuthAbuseProperties properties) {
        return new AuthRateLimiter(properties.maximumKeys(), System::nanoTime);
    }
    @Bean AuthWorkLimiter authWorkLimiter(AuthAbuseProperties properties) {
        return new AuthWorkLimiter(properties.maximumActive());
    }
}
