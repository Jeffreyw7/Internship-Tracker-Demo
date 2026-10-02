package com.campus.tracker.reminder;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReminderProperties {
    @Value("${campus.reminders.mail-enabled:false}") private boolean mailEnabled;
    @Value("${campus.reminders.worker-enabled:true}") private boolean workerEnabled;
    @Value("${campus.reminders.mail-from:}") private String from;
    @Value("${spring.mail.host:}") private String host;
    @PostConstruct void validate() {
        if (mailEnabled && (host.isBlank() || !from.matches("[^\\s@<>]+@[^\\s@<>]+")))
            throw new IllegalArgumentException("Reminder mail requires SMTP_HOST and REMINDER_MAIL_FROM.");
    }
    public boolean mailEnabled() { return mailEnabled; }
    public boolean workerEnabled() { return workerEnabled; }
    public String from() { return from; }
}
