package com.campus.tracker.mail;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SmtpEmailSenderTest {
    @Test void sendsPlainTextWithTrustedOriginAndStableMessageId() throws Exception {
        List<MimeMessage> captured = new ArrayList<>();
        var transport = new JavaMailSenderImpl() {
            @Override public void send(MimeMessage... messages) { captured.addAll(List.of(messages)); }
        };
        var sender = new SmtpEmailSender(transport, OutboxCryptoTest.properties());
        sender.send("student@example.test", "test-secret");
        sender.send("student@example.test", "test-secret");
        for (MimeMessage message : captured) message.saveChanges();
        assertThat(captured).hasSize(2);
        MimeMessage first = captured.get(0);
        assertThat(first.getAllRecipients()[0].toString()).isEqualTo("student@example.test");
        assertThat(first.getFrom()[0].toString()).isEqualTo("capture@example.test");
        assertThat(first.getContentType()).startsWith("text/plain");
        assertThat(first.getContent().toString()).contains("http://localhost:8080/#verify-email=test-secret", "password");
        assertThat(first.getMessageID()).isEqualTo(captured.get(1).getMessageID()).doesNotContain("test-secret");
    }

    @Test void classifiesPermanentRecipientRejectionWithoutLeakingRecipient() {
        var transport = new JavaMailSenderImpl() {
            @Override public void send(MimeMessage... messages) {
                try {
                    throw new MailSendException(Map.of(messages[0], new SMTPAddressFailedException(
                            new InternetAddress("student@example.test"), "RCPT TO", 550, "recipient detail")));
                } catch (jakarta.mail.internet.AddressException exception) { throw new IllegalStateException(exception); }
            }
        };
        assertThatThrownBy(() -> new SmtpEmailSender(transport, OutboxCryptoTest.properties()).send("student@example.test", "test-secret"))
                .isInstanceOf(PermanentDeliveryException.class).hasMessage("Permanent recipient rejection").hasNoCause();
    }
}
