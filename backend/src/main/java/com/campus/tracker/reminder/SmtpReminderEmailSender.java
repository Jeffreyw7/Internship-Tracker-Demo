package com.campus.tracker.reminder;

import com.campus.tracker.mail.PermanentDeliveryException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpReminderEmailSender implements ReminderEmailSender {
    private final ObjectProvider<JavaMailSender> smtp;
    private final ReminderProperties config;
    public SmtpReminderEmailSender(ObjectProvider<JavaMailSender> smtp, ReminderProperties config) { this.smtp=smtp;this.config=config; }
    @Override public void send(String recipient,String subject,String body) {
        var sender=smtp.getIfAvailable();
        if (!config.mailEnabled() || sender==null) throw new IllegalStateException("Reminder SMTP is unavailable.");
        var mail=new SimpleMailMessage();mail.setFrom(config.from());mail.setTo(recipient);mail.setSubject(subject);mail.setText(body);
        try { sender.send(mail); }
        catch (MailAuthenticationException e) { throw new PermanentDeliveryException(); }
        catch (MailSendException e) {
            for (Exception failure:e.getMessageExceptions()) if (failure instanceof jakarta.mail.SendFailedException rejected
                && rejected.getInvalidAddresses()!=null && rejected.getInvalidAddresses().length>0) throw new PermanentDeliveryException();
            throw e;
        }
    }
}
