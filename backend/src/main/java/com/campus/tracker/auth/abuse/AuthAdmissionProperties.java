package com.campus.tracker.auth.abuse;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("campus.auth-admission")
public record AuthAdmissionProperties(
        @NotNull @Pattern(regexp = "[a-zA-Z0-9:_-]{1,100}") String namespace,
        @NotNull @Size(min = 32, max = 512) String hmacSecret) {
    @Override public String toString() { return "AuthAdmissionProperties[redacted]"; }
}
