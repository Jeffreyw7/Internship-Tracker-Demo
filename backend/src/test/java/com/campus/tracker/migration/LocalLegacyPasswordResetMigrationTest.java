package com.campus.tracker.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.*;

class LocalLegacyPasswordResetMigrationTest {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "com.campus.tracker.migration")
    static class MigrationConfiguration {}

    @Test void legacyFileDatabaseKeepsAccountsApplicationsAndResetGuardsAndReceivesImports() throws Exception {
        String url = legacyDatabase();
        runner(url, "local", true).run(context -> assertThat(context).hasNotFailed());
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT email, credential_version, reset_request_cutoff FROM campus_users")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("legacy@example.test");
                assertThat(rows.getLong(2)).isEqualTo(7);
                assertThat(rows.getLong(3)).isEqualTo(19);
            }
            try (var rows = sql.executeQuery("SELECT company,notes,version FROM applications")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("Saved Company");
                assertThat(rows.getString(2)).isEqualTo("Keep this application");
                assertThat(rows.getLong(3)).isEqualTo(3);
            }
            try (var rows = sql.executeQuery("SELECT version,script FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY version")) {
                for (int version = 1; version <= 5; version++) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(Integer.toString(version));
                    if (version == 4) assertThat(rows.getString(2)).isEqualTo("V4__application_imports.sql");
                    if (version == 5) assertThat(rows.getString(2)).isEqualTo("V5__password_reset.sql");
                }
                assertThat(rows.next()).isFalse();
            }
            sql.executeQuery("SELECT * FROM application_imports").close();
            sql.executeQuery("SELECT * FROM application_import_rates").close();
            try (var rows = sql.executeQuery("SELECT enabled,operator_intake_paused,health_intake_paused FROM password_reset_control WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isFalse();
                assertThat(rows.getBoolean(2)).isTrue();
                assertThat(rows.getBoolean(3)).isTrue();
            }
            try (var rows = sql.executeQuery("SELECT scope_key FROM application_import_quota_locks")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("GLOBAL");
            }
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE campus_users SET credential_version=-1"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE password_reset_control SET id=2"))
                    .isInstanceOf(SQLException.class);
        }
        try (var backups = Files.list(directory)) {
            var files = backups.filter(path -> path.getFileName().toString().endsWith(".zip")).toList();
            assertThat(files).hasSize(1);
            try (var archive = new ZipFile(files.get(0).toFile())) {
                var entry = archive.stream().filter(item -> item.getName().endsWith("campus.mv.db")).findFirst().orElseThrow();
                try (var input = archive.getInputStream(entry)) {
                    Files.copy(input, directory.resolve("restored.mv.db"));
                }
            }
            String restoredUrl = url.replace("/campus;", "/restored;");
            try (var connection = DriverManager.getConnection(restoredUrl, "sa", ""); var sql = connection.createStatement();
                    var rows = sql.executeQuery("SELECT script FROM flyway_schema_history WHERE version='4'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("V4__password_reset.sql");
            }
        }
        // The second startup validates the settled lineage normally and does not create another backup.
        runner(url, "local", true).run(context -> assertThat(context).hasNotFailed());
        try (var backups = Files.list(directory)) {
            assertThat(backups.filter(path -> path.toString().endsWith(".zip")).count()).isEqualTo(1);
        }
    }

    @Test void unrecognizedChecksumFailsWithoutRewritingHistoryOrMakingBackup() throws Exception {
        String url = legacyDatabase();
        execute(url, "UPDATE flyway_schema_history SET checksum=123 WHERE version='4'");
        runner(url, "local", true).run(context -> assertThat(context).hasFailed());
        assertLegacyHistoryAndNoBackup(url);
    }

    @Test void modifiedResetSchemaFailsWithoutRewritingHistoryOrMakingBackup() throws Exception {
        String url = legacyDatabase();
        execute(url, "ALTER TABLE password_reset_control DROP COLUMN operator_intake_paused");
        runner(url, "local", true).run(context -> assertThat(context).hasFailed());
        assertLegacyHistoryAndNoBackup(url);
    }

    @Test void optInIsRequiredAndProductionNeverRewritesLegacyHistory() throws Exception {
        String url = legacyDatabase();
        runner(url, "local", false).run(context -> assertThat(context).hasFailed());
        runner(url, "prod", true).run(context -> assertThat(context).hasFailed());
        runner(url, "local,prod", true).run(context -> assertThat(context).hasFailed());
        assertLegacyHistoryAndNoBackup(url);
    }

    @Test void interruptedHistoryShiftFailsClosedAndPreservesData() throws Exception {
        String url = legacyDatabase();
        execute(url, "UPDATE flyway_schema_history SET version='5',script='V5__password_reset.sql' WHERE version='4'");
        runner(url, "local", true).run(context -> assertThat(context).hasFailed());
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT email FROM campus_users")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("legacy@example.test");
        }
        try (var files = Files.list(directory)) {
            assertThat(files.noneMatch(path -> path.toString().endsWith(".zip"))).isTrue();
        }
    }

    @Test void freshAndMainLineageDatabasesUseOrdinaryMigrationsWithoutBackup() throws Exception {
        String url = url();
        Flyway.configure().dataSource(url, "sa", "").target("4").load().migrate();
        runner(url, "local", true).run(context -> assertThat(context).hasNotFailed());
        try (var backups = Files.list(directory)) {
            assertThat(backups.noneMatch(path -> path.toString().endsWith(".zip"))).isTrue();
        }
        String freshUrl = url().replace("/campus;", "/fresh;");
        runner(freshUrl, "local", true).run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner runner(String url, String profile, boolean enabled) {
        return new ApplicationContextRunner().withUserConfiguration(MigrationConfiguration.class)
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=sa",
                        "spring.datasource.password=", "spring.profiles.active=" + profile,
                        "campus.migration.local-legacy-reset-upgrade=" + enabled);
    }

    private String legacyDatabase() throws Exception {
        Path migrations = Files.createDirectory(directory.resolve("legacy-migrations"));
        for (String script : new String[]{"V1__initial_schema.sql", "V2__user_theme_preference.sql",
                "V3__email_verification.sql", "V5__password_reset.sql"}) {
            try (var input = getClass().getResourceAsStream("/db/migration/" + script)) {
                assertThat(input).isNotNull();
                Files.copy(input, migrations.resolve(script.replace("V5__", "V4__")));
            }
        }
        String url = url();
        Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + migrations).load().migrate();
        UUID user = UUID.randomUUID();
        execute(url, "INSERT INTO campus_users(id,email,display_name,password_hash,created_at,credential_version,reset_request_cutoff) "
                + "VALUES ('" + user + "','legacy@example.test','Legacy User','hash',CURRENT_TIMESTAMP,7,19)");
        execute(url, "INSERT INTO applications(id,owner_id,company,role,location,status,notes,version,created_at,updated_at) VALUES ('"
                + UUID.randomUUID() + "','" + user + "','Saved Company','Intern','Remote','APPLIED',"
                + "'Keep this application',3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        execute(url, "UPDATE password_reset_control SET operator_intake_paused=TRUE,health_intake_paused=TRUE WHERE id=1");
        return url;
    }

    private String url() {
        return "jdbc:h2:file:" + directory.resolve("campus").toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0";
    }

    private void execute(String url, String statement) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

    private void assertLegacyHistoryAndNoBackup(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT version, script FROM flyway_schema_history WHERE version IN ('4','5')")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("4");
            assertThat(rows.getString(2)).isEqualTo("V4__password_reset.sql");
            assertThat(rows.next()).isFalse();
        }
        try (var files = Files.list(directory)) {
            assertThat(files.noneMatch(path -> path.toString().endsWith(".zip"))).isTrue();
        }
    }
}
