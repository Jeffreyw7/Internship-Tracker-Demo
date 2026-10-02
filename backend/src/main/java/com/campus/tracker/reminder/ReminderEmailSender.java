package com.campus.tracker.reminder;

/** Delivery boundary used by SMTP in production and a synthetic sender in tests. */
public interface ReminderEmailSender {
    void send(String recipient, String subject, String body);
}
