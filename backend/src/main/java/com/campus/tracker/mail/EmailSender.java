package com.campus.tracker.mail;

@FunctionalInterface
public interface EmailSender {
    void send(String recipient, String token);
    default void send(EmailMessage message) { send(message.recipient(), message.token()); }
}
