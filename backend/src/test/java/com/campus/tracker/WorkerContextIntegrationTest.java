package com.campus.tracker;

import com.campus.tracker.worker.WorkerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class WorkerContextIntegrationTest {
    @Test void mailProcessHasNoHttpControllersSecuritySessionsOrJpa() {
        try (var context = new SpringApplicationBuilder(WorkerApplication.class).web(WebApplicationType.NONE)
                .profiles("test", "worker").run("--campus.worker.role=mail", "--campus.mail.worker-enabled=false",
                        "--spring.datasource.url=jdbc:h2:mem:worker-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "--debug=false", "--logging.level.root=WARN")) {
            assertThat(context).isNotInstanceOf(org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext.class);
            assertThat(context.getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.session.SessionRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(jakarta.persistence.EntityManagerFactory.class)).isEmpty();
            assertThat(context.getBean(com.campus.tracker.mail.EmailOutboxWorker.class)).isNotNull();
        }
    }
}
