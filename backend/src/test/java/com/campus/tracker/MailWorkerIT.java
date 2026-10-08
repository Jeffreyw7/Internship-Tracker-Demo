package com.campus.tracker;

import com.campus.tracker.mail.OutboxClaimRepository;
import com.campus.tracker.mail.EmailOutboxWorker;
import com.campus.tracker.mail.OutboxCrypto;
import com.campus.tracker.support.MailKillHarness;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.tracker.migration.MigrationMain;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class MailWorkerIT {
    @TempDir Path directory;
    @Test void killedWorkerAfterSmtpAcceptanceRetriesTheSameMessageAndToken() throws Exception {
        try (var database = new PostgresPrivilegesIT.TestDatabase(false); var smtp = new SmtpCapture()) {
            var provision = database.psql("psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", database.getUsername(), "-d", "campus", "-f", "provision.sql");
            assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
            MigrationMain.run(Map.of("MIGRATION_DATABASE_URL", database.getJdbcUrl(), "MIGRATION_DATABASE_USERNAME", "campus_migrator",
                    "MIGRATION_DATABASE_PASSWORD", "migration-'quote-password"));
            var admin = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
            var clock = new MutableClock(); var now = clock.instant().atOffset(ZoneOffset.UTC);
            var user = UUID.randomUUID(); var tokenId = UUID.randomUUID(); var job = UUID.randomUUID();
            String token = "synthetic-recovery-token";
            admin.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)", user, "recovery@example.test", "Recovery", "synthetic", now);
            admin.update("INSERT INTO email_verification_tokens(id,user_id,email,token_hash,created_at,expires_at) VALUES(?,?,?,?,?,?)", tokenId, user, "recovery@example.test", "synthetic-hash", now, now.plusDays(1));
            var crypto = new OutboxCrypto(MailKillHarness.properties(), new ObjectMapper());
            var encrypted = crypto.encrypt(job, "recovery@example.test", token);
            admin.update("INSERT INTO email_outbox(id,verification_token_id,payload,nonce,key_id,status,next_attempt_at,created_at) VALUES(?,?,?,?,?,'PENDING',?,?)",
                    job, tokenId, encrypted.payload(), encrypted.nonce(), encrypted.keyId(), now, now);
            var signal = directory.resolve("smtp-accepted.txt");
            var arguments = List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    "com.campus.tracker.support.MailKillHarness", database.getJdbcUrl(), "mail-worker-'quote-password",
                    String.valueOf(smtp.port()), signal.toString(), clock.instant().toString());
            var argumentFile = directory.resolve("mail-worker.args");
            Files.write(argumentFile, arguments.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList());
            var childLog = directory.resolve("mail-child.log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + argumentFile)
                    .redirectErrorStream(true).redirectOutput(childLog.toFile()).start();
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
                assertThat(signal).withFailMessage("Worker did not reach SMTP acceptance: %s", Files.readString(childLog)).exists();
                assertThat(admin.queryForObject("SELECT status FROM email_outbox WHERE id=?", String.class, job)).isEqualTo("SENDING");
                process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                clock.advance(Duration.ofSeconds(121));
                var source = new DriverManagerDataSource(database.getJdbcUrl(), "campus_mail_worker", "mail-worker-'quote-password");
                var worker = new EmailOutboxWorker(new OutboxClaimRepository(new JdbcTemplate(source), new DataSourceTransactionManager(source), clock),
                        crypto, MailKillHarness.sender(smtp.port()), clock);
                assertThat(worker.deliverOne()).isTrue();
                assertThat(admin.queryForObject("SELECT status FROM email_outbox WHERE id=?", String.class, job)).isEqualTo("SENT");
                assertThat(admin.queryForObject("SELECT attempts FROM email_outbox WHERE id=?", Integer.class, job)).isEqualTo(2);
                assertThat(admin.queryForObject("SELECT payload FROM email_outbox WHERE id=?", String.class, job)).isNull();
                assertThat(admin.queryForObject("SELECT consumed_at FROM email_verification_tokens WHERE id=?", OffsetDateTime.class, tokenId)).isNull();
                var first = smtp.messages.poll(5, TimeUnit.SECONDS); var second = smtp.messages.poll(5, TimeUnit.SECONDS);
                assertThat(first).isNotNull(); assertThat(second).isNotNull();
                assertThat(messageId(first)).isEqualTo(messageId(second));
                assertThat(first).contains("#verify-email=" + token); assertThat(second).contains("#verify-email=" + token);
                assertThat(worker.deliverOne()).isFalse();
            } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        }
    }
    private static String messageId(String message) {
        return message.lines().filter(line -> line.startsWith("Message-ID:")).findFirst().orElseThrow();
    }
    /** Loopback-only real SMTP acceptance, without any external delivery. */
    private static class SmtpCapture implements AutoCloseable {
        final ServerSocket server;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> loop;
        SmtpCapture() throws IOException {
            server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            loop = executor.submit(() -> {
                try {
                    while (!server.isClosed()) try (var socket = server.accept()) {
                        socket.setSoTimeout(10000);
                        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        var output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                        output.print("220 fixture\r\n"); output.flush();
                        String line;
                        while ((line = input.readLine()) != null) {
                            if (line.equalsIgnoreCase("DATA")) {
                                output.print("354 continue\r\n"); output.flush();
                                var message = new StringBuilder();
                                while ((line = input.readLine()) != null && !line.equals(".")) message.append(line).append("\r\n");
                                messages.add(message.toString()); output.print("250 accepted\r\n");
                            } else if (line.equalsIgnoreCase("QUIT")) { output.print("221 bye\r\n"); output.flush(); break; }
                            else output.print("250 fixture\r\n");
                            output.flush();
                        }
                    }
                } catch (IOException failure) { if (!server.isClosed()) throw new UncheckedIOException(failure); }
            });
        }
        int port() { return server.getLocalPort(); }
        public void close() throws Exception { server.close(); executor.shutdownNow(); loop.get(5, TimeUnit.SECONDS); }
    }
    @Test void restrictedWorkersSkipLockedJobsAndFenceExpiredOwners() throws Exception {
        try (var database = new PostgresPrivilegesIT.TestDatabase(false)) {
            var provision = database.psql("psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", database.getUsername(), "-d", "campus", "-f", "provision.sql");
            assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
            MigrationMain.run(Map.of("MIGRATION_DATABASE_URL", database.getJdbcUrl(), "MIGRATION_DATABASE_USERNAME", "campus_migrator",
                    "MIGRATION_DATABASE_PASSWORD", "migration-'quote-password"));
            var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            var admin = new JdbcTemplate(source);
            var clock = new MutableClock();
            var now = clock.instant().atOffset(ZoneOffset.UTC);
            var user = UUID.randomUUID(); var token = UUID.randomUUID();
            admin.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)", user, "worker@example.test", "Test", "synthetic", now);
            admin.update("INSERT INTO email_verification_tokens(id,user_id,email,token_hash,created_at,expires_at) VALUES(?,?,?,?,?,?)", token, user, "worker@example.test", "synthetic-hash", now, now.plusDays(1));
            var locked = UUID.randomUUID(); var available = UUID.randomUUID();
            for (var id : java.util.List.of(locked, available)) admin.update("INSERT INTO email_outbox(id,verification_token_id,payload,nonce,key_id,status,next_attempt_at,created_at) VALUES(?,?,?,?,?,'PENDING',?,?)",
                    id, token, "synthetic-encrypted", "synthetic-nonce", "synthetic-key", now, now);
            var workerSource = new DriverManagerDataSource(database.getJdbcUrl(), "campus_mail_worker", "mail-worker-'quote-password");
            var workerJdbc = new JdbcTemplate(workerSource);
            var first = new OutboxClaimRepository(workerJdbc, new DataSourceTransactionManager(workerSource), clock);
            var second = new OutboxClaimRepository(workerJdbc, new DataSourceTransactionManager(workerSource), clock);
            try (var connection = source.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM email_outbox WHERE id=? FOR UPDATE")) {
                    statement.setObject(1, locked); statement.executeQuery().close();
                }
                // A regression to ordinary FOR UPDATE must fail promptly instead of hanging this check.
                workerJdbc.setQueryTimeout(2);
                long start = System.nanoTime();
                var claimed = second.claim(2, UUID.randomUUID());
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
                assertThat(claimed).extracting(OutboxClaimRepository.ClaimedMail::id).containsExactly(available);
                assertThat(second.eligible(claimed.get(0))).isTrue();
                connection.rollback();
            }
            var old = first.claim(2, UUID.randomUUID());
            assertThat(old).extracting(OutboxClaimRepository.ClaimedMail::id).containsExactly(locked);
            assertThat(second.claim(2, UUID.randomUUID())).isEmpty();
            clock.advance(Duration.ofSeconds(121));
            assertThat(first.finish(old.get(0), "SENT", null)).isFalse();
            var replacements = second.claim(2, UUID.randomUUID());
            assertThat(replacements).hasSize(2);
            assertThat(first.retry(old.get(0), now.plusMinutes(10))).isFalse();
            for (var job : replacements) assertThat(second.finish(job, "SENT", null)).isTrue();
            assertThat(admin.queryForList("SELECT payload FROM email_outbox", String.class)).containsOnlyNulls();
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM campus_users", Integer.class)).isEqualTo(1);
            assertThat(admin.queryForObject("SELECT consumed_at FROM email_verification_tokens", OffsetDateTime.class)).isNull();
        }
    }
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-04T10:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
