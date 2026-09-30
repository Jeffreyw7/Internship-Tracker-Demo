package com.campus.tracker.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SmtpEmailSender implements EmailSender {
    private final JavaMailSender transport;
    private final MailProperties properties;

    public SmtpEmailSender(JavaMailSender transport, MailProperties properties) {
        this.transport = transport; this.properties = properties;
    }

    @Override public void send(String recipient, String token) {
        try {
            // SMTP may deliver twice after a crash. Reuse the token and a stable Message-ID.
            String messageId = "<verification-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8))) + "@campus-verification>";
            MimeMessage message = new MimeMessage(transport.createMimeMessage().getSession()) {
                @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID", messageId); }
            };
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(properties.sender()); helper.setTo(recipient);
            helper.setSubject("Verify your Campus Internship Tracker email");
            helper.setText("Confirm your email address using the password you chose at signup:\n\n"
                    + properties.publicOrigin() + "/#verify-email=" + token
                    + "\n\nIf you did not create this account, ignore this message.\n", false);
            transport.send(message);
        } catch (MailSendException exception) {
            for (Exception failure : exception.getMessageExceptions()) {
                for (Throwable cause = failure; cause != null; cause = next(cause)) {
                    if (cause instanceof SMTPAddressFailedException rejected && rejected.getReturnCode() >= 500) {
                        throw new PermanentDeliveryException();
                    }
                }
            }
            throw new IllegalStateException("SMTP delivery failed");
        } catch (Exception exception) {
            throw new IllegalStateException("SMTP delivery failed");
        }
    }

    private Throwable next(Throwable failure) {
        Throwable next = failure instanceof MessagingException mail ? mail.getNextException() : failure.getCause();
        return next == failure ? null : next;
    }
}
