package com.campus.tracker.mail;

@FunctionalInterface
public interface EmailSender {
    void send(String recipient, String token);
}
