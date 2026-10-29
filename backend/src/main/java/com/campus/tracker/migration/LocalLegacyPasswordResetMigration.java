package com.campus.tracker.migration;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One-time bridge for the unpublished local demo's V4 password-reset lineage. */
@Component
@Profile("local & !prod")
@ConditionalOnProperty(name = "campus.migration.local-legacy-reset-upgrade", havingValue = "true")
public class LocalLegacyPasswordResetMigration implements FlywayMigrationStrategy {
    private static final Logger log = LoggerFactory.getLogger(LocalLegacyPasswordResetMigration.class);
    private static final String HISTORY = "\"flyway_schema_history\"";
    private static final Set<String> IMPORT_TABLES = Set.of("application_imports", "application_import_quota_locks", "application_import_rates");

    @Override public void migrate(Flyway flyway) {
        Path backup = null;
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:h2:file:")
                    || !"flyway_schema_history".equals(flyway.getConfiguration().getTable())) {
                throw new IllegalStateException("Local legacy reset upgrade requires file-backed H2 and the default Flyway history table");
            }
            if (!tableExists(connection, "flyway_schema_history")) {
                flyway.migrate();
                return;
            }
            List<AppliedMigration> history = readHistory(connection);
            if (history.stream().noneMatch(row -> "V4__password_reset.sql".equals(row.script()))) {
                // Includes settled main databases. Flyway must reject ambiguous/partial histories normally.
                flyway.migrate();
                return;
            }
            validateLegacyHistory(flyway, history);
            validateLegacySchema(connection);
            backup = backup(connection);
            connection.setAutoCommit(false);
            try {
                if (!readHistory(connection).equals(history)) throw new IllegalStateException("Migration history changed during local upgrade");
                try (var update = connection.prepareStatement("UPDATE " + HISTORY
                        + " SET \"version\"='5', \"script\"='V5__password_reset.sql', \"description\"='password reset'"
                        + " WHERE \"installed_rank\"=4 AND \"version\"='4' AND \"script\"='V4__password_reset.sql'"
                        + " AND \"checksum\"=? AND \"success\"=TRUE")) {
                    update.setInt(1, history.get(3).checksum());
                    if (update.executeUpdate() != 1) throw new IllegalStateException("Legacy migration history no longer matches");
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
            // This flag is confined to this verified legacy upgrade, never the normal configuration.
            Flyway.configure().configuration(flyway.getConfiguration()).outOfOrder(true).load().migrate();
            log.info("Local password-reset database upgraded; original H2 backup: {}", backup);
        } catch (Exception failure) {
            String guidance = backup == null
                    ? "No local lineage rewrite was authorized; verify the exact legacy database and migration history."
                    : "Stop the app and restore the original H2 database from " + backup + " before retrying; do not repair or delete migration history.";
            throw new IllegalStateException("Local password-reset lineage upgrade failed. " + guidance, failure);
        }
    }

    private void validateLegacyHistory(Flyway flyway, List<AppliedMigration> history) throws Exception {
        // Let Flyway itself derive checksums. Never repair or reimplement its checksum algorithm.
        String referenceUrl = "jdbc:h2:mem:local-lineage-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try {
            Flyway.configure().dataSource(referenceUrl, "sa", "").locations(flyway.getConfiguration().getLocations())
                    .target("5").load().migrate();
            try (var reference = DriverManager.getConnection(referenceUrl, "sa", "")) {
                List<AppliedMigration> resolved = readHistory(reference);
                if (resolved.size() != 5 || history.size() != 4) throw new IllegalStateException("Unexpected legacy migration history length");
                for (int index = 0; index < 3; index++) {
                    if (!resolved.get(index).equals(history.get(index))) throw new IllegalStateException("Legacy V1-V3 history does not match resolved migrations");
                }
                AppliedMigration reset = resolved.get(4);
                AppliedMigration expected = new AppliedMigration(4, "4", reset.description(), reset.type(),
                        "V4__password_reset.sql", reset.checksum(), true);
                if (!"5".equals(reset.version()) || !"V5__password_reset.sql".equals(reset.script())
                        || !expected.equals(history.get(3))) {
                    throw new IllegalStateException("Legacy V4 reset history does not match resolved V5 reset migration");
                }
            }
        } finally {
            try (var reference = DriverManager.getConnection(referenceUrl, "sa", ""); var sql = reference.createStatement()) {
                sql.execute("SHUTDOWN");
            }
        }
    }

    private void validateLegacySchema(Connection connection) throws SQLException {
        Map<String, Set<String>> columns = new HashMap<>();
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(
                "SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=CURRENT_SCHEMA")) {
            while (rows.next()) columns.computeIfAbsent(rows.getString(1), ignored -> new HashSet<>()).add(rows.getString(2));
        }
        if (IMPORT_TABLES.stream().anyMatch(columns::containsKey)) throw new IllegalStateException("Legacy database already contains import schema");
        Map<String, Set<String>> required = Map.of(
                "campus_users", Set.of("credential_version", "reset_request_cutoff"),
                "password_reset_control", Set.of("id", "enabled", "operator_intake_paused", "health_intake_paused"),
                "password_reset_requests", Set.of("id", "request_order", "payload", "key_id", "nonce", "status", "attempts", "next_attempt_at",
                        "lease_owner", "lease_expires_at", "created_at", "expires_at", "completed_at", "failure_category"),
                "password_reset_tokens", Set.of("id", "request_id", "user_id", "email", "token_hash", "credential_version", "created_at", "expires_at", "consumed_at", "revoked_at"),
                "auth_security_events", Set.of("id", "completion_id", "event_type", "occurred_at", "account_subject", "correlation_id"),
                "email_outbox", Set.of("verification_token_id", "message_type", "password_reset_token_id"));
        for (var table : required.entrySet()) {
            if (!columns.getOrDefault(table.getKey(), Set.of()).containsAll(table.getValue())) {
                throw new IllegalStateException("Legacy reset schema is incomplete: " + table.getKey());
            }
        }
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(
                "SELECT COUNT(*) FROM information_schema.sequences WHERE sequence_schema=CURRENT_SCHEMA AND sequence_name='auth_security_event_order'")) {
            rows.next();
            if (rows.getInt(1) != 1) throw new IllegalStateException("Legacy reset sequence is missing");
        }
    }

    private Path backup(Connection connection) throws Exception {
        String databasePath;
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT DATABASE_PATH()")) {
            rows.next();
            databasePath = rows.getString(1);
        }
        if (databasePath == null) throw new IllegalStateException("Cannot determine local H2 file location");
        Path backup = Path.of(databasePath + ".before-reset-lineage-" + UUID.randomUUID() + ".zip");
        if (Files.exists(backup)) throw new IllegalStateException("Local database backup path already exists");
        try (var sql = connection.prepareStatement("BACKUP TO ?")) {
            sql.setString(1, backup.toString());
            sql.execute();
        }
        if (!Files.isRegularFile(backup) || Files.size(backup) == 0) throw new IllegalStateException("Local H2 backup was not created");
        log.info("Created original local H2 backup before migration history change: {}", backup);
        return backup;
    }

    private boolean tableExists(Connection connection, String name) throws SQLException {
        try (var sql = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=CURRENT_SCHEMA AND table_name=?")) {
            sql.setString(1, name);
            try (var rows = sql.executeQuery()) { rows.next(); return rows.getInt(1) == 1; }
        }
    }

    private List<AppliedMigration> readHistory(Connection connection) throws SQLException {
        var history = new ArrayList<AppliedMigration>();
        // H2 Flyway records its own table creation at rank -1; it is not an applied migration.
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT \"installed_rank\", \"version\", \"description\", \"type\", \"script\", \"checksum\", \"success\" FROM " + HISTORY + " WHERE \"type\"<>'TABLE' ORDER BY \"installed_rank\"")) {
            while (rows.next()) history.add(new AppliedMigration(rows.getInt(1), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getString(5), (Integer) rows.getObject(6), rows.getBoolean(7)));
        }
        return history;
    }

    private record AppliedMigration(int rank, String version, String description, String type, String script, Integer checksum, boolean success) {}
}
