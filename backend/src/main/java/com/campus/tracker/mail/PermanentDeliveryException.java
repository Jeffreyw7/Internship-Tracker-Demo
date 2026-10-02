package com.campus.tracker.mail;
/** A non-retryable SMTP rejection. Carries no provider response or recipient details. */
public class PermanentDeliveryException extends RuntimeException {
    public PermanentDeliveryException(){super("Mail delivery rejected");}
}
