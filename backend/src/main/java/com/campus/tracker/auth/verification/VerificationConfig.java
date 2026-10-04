package com.campus.tracker.auth.verification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class VerificationConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
}
