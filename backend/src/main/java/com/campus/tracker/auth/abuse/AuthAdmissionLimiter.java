package com.campus.tracker.auth.abuse;

public interface AuthAdmissionLimiter {
    AuthRateLimiter.Decision acquire(String key, AuthRateLimiter.Policy policy);
}
