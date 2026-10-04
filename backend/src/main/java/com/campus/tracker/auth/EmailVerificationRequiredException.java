package com.campus.tracker.auth;

import org.springframework.security.core.AuthenticationException;

public final class EmailVerificationRequiredException extends AuthenticationException {
    public EmailVerificationRequiredException() { super("Email verification is required."); }
}
