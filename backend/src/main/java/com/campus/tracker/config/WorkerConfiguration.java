package com.campus.tracker.config;

import com.campus.tracker.mail.*;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
public class WorkerConfiguration {
    @Bean org.springframework.beans.factory.InitializingBean workerRolePolicy(
            @Value("${campus.worker.role}") String role, org.springframework.core.env.Environment environment,
            org.springframework.boot.autoconfigure.jdbc.DataSourceProperties database) {
        return () -> {
            if (!role.equals("mail") && !role.equals("export")) throw new IllegalArgumentException("Worker role must be mail or export");
            if (role.equals("export")) throw new IllegalArgumentException("Export worker is not configured yet");
            if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))
                    && !("campus_" + role + "_worker").equals(database.getUsername()))
                throw new IllegalArgumentException("Worker requires its separate database role");
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "campus.worker.role", havingValue = "mail")
    @Import({MailConfiguration.class, OutboxKeyRing.class, OutboxCrypto.class, OutboxClaimRepository.class,
            OutboxMaintenance.class, EmailOutboxWorker.class, MailMetrics.class})
    static class MailRole {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }
}
