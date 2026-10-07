package com.campus.tracker.mail;

/** A permanent SMTP recipient rejection; deliberately contains no recipient details. */
public class PermanentDeliveryException extends RuntimeException {
    public PermanentDeliveryException() { super("Permanent recipient rejection"); }
}
