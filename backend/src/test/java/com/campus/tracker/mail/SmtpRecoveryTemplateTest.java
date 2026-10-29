package com.campus.tracker.mail;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class SmtpRecoveryTemplateTest {
    @Test void totalSendDeadlineClosesTheSocketInsteadOfOnlyInterruptingTheFuture() throws Exception {
        var socket = new AtomicReference<Socket>();
        var stopped = new CountDownLatch(1);
        var capture = new JavaMailSenderImpl() {
            @Override public void send(MimeMessage input) {
                try {
                    var factory = (javax.net.SocketFactory)getJavaMailProperties().get("mail.smtp.socketFactory");
                    socket.set(factory.createSocket());
                    while(!socket.get().isClosed()) {
                        try { Thread.sleep(5); } catch(InterruptedException ignored) { }
                    }
                } catch(Exception exception) { throw new IllegalStateException(exception); }
                finally { stopped.countDown(); }
            }
        };
        var sender = new SmtpEmailSender(capture, properties(), Duration.ofMillis(100));
        assertThatThrownBy(() -> sender.send(new EmailMessage(UUID.randomUUID(), EmailMessage.Type.PASSWORD_RESET_LINK,
                "student@example.test", "synthetic-reset", null))).isInstanceOf(IllegalStateException.class).hasMessage("SMTP delivery failed");
        assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(socket.get().isClosed()).isTrue();
    }
    @Test void resetLinkUsesItsOwnFragmentAndWarnsAgainstUnsolicitedRecovery() throws Exception {
        var capture = new Capture(); var sender = new SmtpEmailSender(capture, properties());
        UUID job = UUID.randomUUID();
        sender.send(new EmailMessage(job, EmailMessage.Type.PASSWORD_RESET_LINK, "student@example.test", "synthetic-reset", null));
        assertThat(capture.message.getSubject()).contains("Reset");
        assertThat(capture.message.getContent().toString()).contains("/#reset-password=synthetic-reset", "ignore").doesNotContain("#verify-email=");
        assertThat(capture.message.getMessageID()).contains(job.toString());
    }
    @Test void changedNoticeIncludesUtcTimeAndConfiguredReportDestinationWithoutSecrets() throws Exception {
        var capture = new Capture(); var sender = new SmtpEmailSender(capture, properties());
        sender.send(new EmailMessage(UUID.randomUUID(), EmailMessage.Type.PASSWORD_CHANGED, "student@example.test", null,
                Instant.parse("2026-10-11T12:34:56Z")));
        assertThat(capture.message.getContent().toString()).contains("2026-10-11T12:34:56Z", "UTC", "https://example.test/help", "did not")
                .doesNotContain("#reset-password=", "#verify-email=");
    }
    private MailProperties properties() {
        return new MailProperties("https://example.test", "sender@example.test", "prod-test", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                true, false, Map.of(), "https://example.test/help");
    }
    private static final class Capture extends JavaMailSenderImpl {
        private MimeMessage message;
        @Override public void send(MimeMessage input) { message = input; }
    }
}
