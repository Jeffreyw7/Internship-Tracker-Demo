package com.campus.tracker.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;

@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfiguration {
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender emailSender(JavaMailSender transport, MailProperties properties,
                            org.springframework.boot.autoconfigure.mail.MailProperties smtp) {
        if (properties.production() && (smtp.getHost() == null || smtp.getHost().isBlank()
                || "localhost".equalsIgnoreCase(smtp.getHost()) || "127.0.0.1".equals(smtp.getHost())
                || smtp.getUsername() == null || smtp.getUsername().isBlank()
                || smtp.getPassword() == null || smtp.getPassword().isBlank())) {
            throw new IllegalArgumentException("Production SMTP host and credentials are required");
        }
        return new SmtpEmailSender(transport, properties);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "campus.mail.worker-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler {
        private final EmailOutboxWorker worker;
        private final ThreadPoolExecutor deliveries = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "campus-mail-worker"); thread.setDaemon(true); return thread; });
        Scheduler(EmailOutboxWorker worker) { this.worker = worker; }
        @Scheduled(fixedDelayString = "${campus.mail.poll-interval-ms:5000}")
        public void deliver() {
            for(int i=0;i<2;i++) {
                try { deliveries.execute(worker::deliverPending); }
                catch(RejectedExecutionException saturated) { break; }
            }
        }
        @PreDestroy public void stop() { deliveries.shutdown(); }
    }
}
