package com.campus.tracker.support;

import com.campus.tracker.mail.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Test-only subprocess: pause after actual SMTP acceptance, before fenced completion. */
public class MailKillHarness {
    public static MailProperties properties() {
        return new MailProperties("http://localhost", "fixture@example.test", "fixture",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", false, true);
    }
    public static SmtpEmailSender sender(int port) {
        var transport = new JavaMailSenderImpl();
        transport.setHost("127.0.0.1"); transport.setPort(port);
        for (var name : java.util.List.of("connectiontimeout", "timeout", "writetimeout"))
            transport.getJavaMailProperties().setProperty("mail.smtp." + name, "5000");
        return new SmtpEmailSender(transport, properties());
    }
    public static void main(String[] args) throws Exception {
        var source = new DriverManagerDataSource(args[0], "campus_mail_worker", args[1]);
        var clock = Clock.fixed(Instant.parse(args[4]), ZoneOffset.UTC);
        var smtp = sender(Integer.parseInt(args[2]));
        EmailSender pauseAfterAcceptance = (recipient, token) -> {
            smtp.send(recipient, token);
            try {
                Files.writeString(Path.of(args[3]), "accepted");
                Thread.sleep(Duration.ofHours(1).toMillis());
            } catch (Exception failure) { throw new IllegalStateException("Test interrupted", failure); }
        };
        new EmailOutboxWorker(new OutboxClaimRepository(new JdbcTemplate(source),
                new DataSourceTransactionManager(source), clock),
                new OutboxCrypto(properties(), new ObjectMapper()), pauseAfterAcceptance, clock).deliverOne();
    }
}
