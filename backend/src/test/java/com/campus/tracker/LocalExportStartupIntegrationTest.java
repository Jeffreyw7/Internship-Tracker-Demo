package com.campus.tracker;

import com.campus.tracker.export.*;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Ordinary local startup must process CSV jobs without a test-only worker override. */
class LocalExportStartupIntegrationTest {
    @TempDir Path directory;
    @Test void defaultLocalStartupProcessesPrivateExports() throws Exception {
        try (var context = new SpringApplicationBuilder(CampusApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:h2:mem:default-export-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--campus.mail.worker-enabled=false", "--campus.export.file-root=" + directory.resolve("objects"),
                "--campus.export.spool-root=" + directory.resolve("spool"), "--logging.level.root=WARN")) {
            assertThat(context.getBeansOfType(ExportWorker.class)).hasSize(1);
            var owner = UUID.randomUUID();
            context.getBean(JdbcTemplate.class).update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)",
                    owner, owner + "@example.test", "Local export", "synthetic", OffsetDateTime.now(ZoneOffset.UTC));
            var api = context.getBean(ExportService.class); var job = api.create(owner);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (java.util.Set.of(ExportDtos.State.PENDING, ExportDtos.State.RUNNING).contains(api.get(owner, job.id()).state())
                    && System.nanoTime() < deadline) Thread.sleep(50);
            assertThat(api.get(owner, job.id()).state()).isEqualTo(ExportDtos.State.READY);
            try (var csv = api.download(owner, job.id()).resource().getInputStream()) {
                assertThat(new String(csv.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).startsWith("\uFEFF\"Company\"");
            }
        }
    }
}
