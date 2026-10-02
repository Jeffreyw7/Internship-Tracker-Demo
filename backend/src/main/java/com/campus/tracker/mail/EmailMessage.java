package com.campus.tracker.mail;
import java.time.Instant;
public record EmailMessage(String type, String recipient, String token, Instant timestamp) {
    @Override public String toString(){return "EmailMessage[type="+type+", secrets=REDACTED]";}
}
