package com.campus.tracker.auth.abuse;

public final class AuthThrottleException extends RuntimeException {
    public static final String MESSAGE = "Too many authentication requests. Please try again shortly.";
    private final long retryAfterSeconds;
    public AuthThrottleException(long retryAfterSeconds) {
        super(MESSAGE); this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
