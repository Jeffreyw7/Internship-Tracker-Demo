package com.campus.tracker.auth.abuse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("campus.auth-abuse")
public record AuthAbuseProperties(
        @Min(1) @Max(1000000) int maximumKeys,
        @Min(1) @Max(1000) int maximumActive,
        @NotNull @Valid AuthRateLimiter.Policy loginIp,
        @NotNull @Valid AuthRateLimiter.Policy loginEmail,
        @NotNull @Valid AuthRateLimiter.Policy registrationIp,
        @NotNull @Valid AuthRateLimiter.Policy csrfIp) {}
