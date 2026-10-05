package com.campus.tracker.auth.abuse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@Profile("distributed")
@EnableConfigurationProperties(AuthAdmissionProperties.class)
public class DistributedAdmissionConfiguration {
    @Bean AuthAdmissionLimiter authAbuseRateLimiter(StringRedisTemplate redis, AuthAdmissionProperties admission,
            AuthAbuseProperties policies, org.springframework.core.env.Environment environment) {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))
                && admission.namespace().contains(":local:")) throw new IllegalArgumentException("Configure a deployment admission namespace");
        return new RedisAuthRateLimiter(redis, admission, policies.maximumKeys());
    }
}
