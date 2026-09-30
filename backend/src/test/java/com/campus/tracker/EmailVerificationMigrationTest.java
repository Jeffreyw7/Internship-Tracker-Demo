package com.campus.tracker;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class EmailVerificationMigrationTest {
    @Test void existingAccountsAreExemptAndNewAccountsAreNot() throws Exception {
        String url = "jdbc:h2:mem:verification-migration-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
        UUID existingId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO campus_users(id,email,display_name,password_hash,created_at,theme_preference) "
                    + "VALUES ('" + existingId + "','existing@example.test','Existing','hash',CURRENT_TIMESTAMP,'system')");
            Flyway.configure().dataSource(url, "sa", "").load().migrate();
            sql.execute("INSERT INTO campus_users(id,email,display_name,password_hash,created_at,theme_preference) "
                    + "VALUES ('" + newId + "','new@example.test','New','hash',CURRENT_TIMESTAMP,'system')");
            try (var result = sql.executeQuery("SELECT id,email_verified_at,email_verification_exempt FROM campus_users ORDER BY email")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("email_verified_at")).isNull();
                assertThat(result.getBoolean("email_verification_exempt")).isTrue();
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("email_verified_at")).isNull();
                assertThat(result.getBoolean("email_verification_exempt")).isFalse();
            }
        }
    }
}
