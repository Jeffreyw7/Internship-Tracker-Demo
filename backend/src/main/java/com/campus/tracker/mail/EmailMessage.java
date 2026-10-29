package com.campus.tracker.mail;

import java.time.Instant;
import java.util.UUID;

/** Purpose travels with the job so a token-bearing payload cannot become a notification. */
public record EmailMessage(UUID jobId, Type type, String recipient, String token, Instant occurredAt) {
    public enum Type { EMAIL_VERIFICATION, PASSWORD_RESET_LINK, PASSWORD_CHANGED }
    @Override public String toString() { return "EmailMessage[jobId=" + jobId + ", type=" + type + "]"; }
}
