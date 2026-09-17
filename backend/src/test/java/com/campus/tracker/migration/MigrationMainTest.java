package com.campus.tracker.migration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class MigrationMainTest {
 @Test void missingSecretsFailWithoutEchoingValues() {
  assertThatThrownBy(() -> MigrationMain.run(Map.of("MIGRATION_DATABASE_PASSWORD", "secret-marker")))
   .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MIGRATION_DATABASE_URL").hasMessageNotContaining("secret-marker");
 }
 @Test void refusesRuntimeCredentialsBeforeConnecting() {
  assertThatThrownBy(() -> MigrationMain.run(Map.of("MIGRATION_DATABASE_URL", "jdbc:postgresql://localhost/campus", "MIGRATION_DATABASE_USERNAME", "campus_runtime", "MIGRATION_DATABASE_PASSWORD", "secret-marker")))
   .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("campus_migrator").hasMessageNotContaining("secret-marker");
 }
}
