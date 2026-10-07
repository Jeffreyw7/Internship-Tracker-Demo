package com.campus.tracker;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ThemeMigrationTest {
    @Test void existingAccountsReceiveSystemAndInvalidValuesAreRejected() throws Exception {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES ('" + UUID.randomUUID()
                    + "','migration@example.test','Existing User','synthetic-hash',CURRENT_TIMESTAMP)");
            Flyway.configure().dataSource(url, "sa", "").load().migrate();
            try (var result = sql.executeQuery("SELECT theme_preference FROM campus_users")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("system");
            }
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE campus_users SET theme_preference='sepia'"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
