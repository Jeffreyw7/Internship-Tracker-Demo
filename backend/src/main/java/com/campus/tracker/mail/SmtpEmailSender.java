package com.campus.tracker.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SmtpEmailSender implements EmailSender {
    private final JavaMailSender transport;
    private final MailProperties properties;
    private final TrackingSocketFactory sockets = new TrackingSocketFactory();
    private final long deadlineMillis;
    private final ThreadPoolExecutor sends = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "campus-smtp"); thread.setDaemon(true); return thread; });

    public SmtpEmailSender(JavaMailSender transport, MailProperties properties) { this(transport, properties, Duration.ofSeconds(55)); }
    SmtpEmailSender(JavaMailSender transport, MailProperties properties, Duration deadline) {
        this.transport = transport; this.properties = properties;
        if (deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofSeconds(55)) > 0) throw new IllegalArgumentException("Invalid SMTP deadline");
        deadlineMillis = Math.max(1, deadline.toMillis());
        if (transport instanceof JavaMailSenderImpl configured) {
            configured.getJavaMailProperties().put("mail.smtp.socketFactory", sockets);
            configured.getJavaMailProperties().setProperty("mail.smtp.socketFactory.fallback", "false");
        } else if(properties.production()) throw new IllegalArgumentException("Production SMTP requires bounded socket transport");
    }
    @PreDestroy public void stop() { sends.shutdown(); }
    @Override public void send(String recipient, String token) {
        try {
            // Legacy programmatic callers retain stable IDs; durable jobs use their own UUID.
            UUID job = UUID.nameUUIDFromBytes(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
            send(new EmailMessage(job, EmailMessage.Type.EMAIL_VERIFICATION, recipient, token, null));
        } catch (PermanentDeliveryException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("SMTP delivery failed"); }
    }
    @Override public void send(EmailMessage input) {
        TrackingSocketFactory.Attempt attempt = new TrackingSocketFactory.Attempt();
        Future<?> future = null;
        try {
            MimeMessage message = message(input);
            future = sends.submit(() -> {
                sockets.begin(attempt);
                try { transport.send(message); }
                finally { sockets.end(); attempt.close(); }
            });
            future.get(deadlineMillis, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            attempt.close();
            if(future != null) future.cancel(true);
            if(exception instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable failure = exception instanceof ExecutionException ? exception.getCause() : exception;
            if(permanent(failure)) throw new PermanentDeliveryException();
            throw new IllegalStateException("SMTP delivery failed");
        }
    }
    private MimeMessage message(EmailMessage input) throws Exception {
        String messageId = "<campus-" + input.type().name().toLowerCase(java.util.Locale.ROOT) + "-" + input.jobId() + "@campus-mail>";
        MimeMessage message = new MimeMessage(transport.createMimeMessage().getSession()) {
            @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID", messageId); }
        };
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(properties.sender()); helper.setTo(input.recipient());
        String text;
        switch(input.type()) {
            case EMAIL_VERIFICATION -> {
                helper.setSubject("Verify your Campus Internship Tracker email");
                text = "Confirm your email address using your current account password (the one chosen at signup unless you reset it):\n\n"
                        + properties.publicOrigin() + "/#verify-email=" + input.token()
                        + "\n\nIf you did not create this account, ignore this message.\n";
            }
            case PASSWORD_RESET_LINK -> {
                helper.setSubject("Reset your Campus Internship Tracker password");
                text = "Choose a new password using this link. It expires 30 minutes after issuance:\n\n"
                        + properties.publicOrigin() + "/#reset-password=" + input.token()
                        + "\n\nIf you did not request a password reset, ignore this message. Requesting a link does not change your password.\n";
            }
            case PASSWORD_CHANGED -> {
                if(input.occurredAt() == null || input.token() != null) throw new IllegalArgumentException();
                helper.setSubject("Your Campus Internship Tracker password changed");
                text = "Your password was changed at " + input.occurredAt() + " (UTC).\n\n"
                        + "If you did not make this change, secure your mailbox and request a new password reset from the application.\n"
                        + (properties.helpDestination() == null ? "" : "Report unexpected activity and get help: " + properties.helpDestination() + "\n");
            }
            default -> throw new IllegalArgumentException();
        }
        helper.setText(text, false); message.saveChanges(); return message;
    }
    private boolean permanent(Throwable failure) {
        if(failure instanceof MailSendException batch) {
            for(Exception item : batch.getMessageExceptions()) if(permanent(item)) return true;
        }
        for(Throwable cause = failure; cause != null; cause = next(cause))
            if(cause instanceof SMTPAddressFailedException rejected && rejected.getReturnCode() >= 500) return true;
        return false;
    }
    private Throwable next(Throwable failure) {
        Throwable next = failure instanceof MessagingException mail ? mail.getNextException() : failure.getCause();
        return next == failure ? null : next;
    }
}
