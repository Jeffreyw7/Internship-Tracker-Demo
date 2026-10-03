package com.campus.tracker;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Upgrade an existing V3 database, rather than merely creating an empty V4 schema. */
class PostingMigrationTest {
    @Test void h2UpgradePreservesExistingApplicationsAndRelatedRows() throws Exception {
        migrate("jdbc:h2:mem:posting_upgrade_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    }
    @Test void postgresUpgradePreservesExistingApplicationsAndRelatedRowsWhenConfigured() throws Exception {
        String url=System.getenv("POSTING_TEST_POSTGRES_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url!=null,"Set POSTING_TEST_POSTGRES_URL for a disposable PostgreSQL database.");
        migrate(url,System.getenv("POSTING_TEST_POSTGRES_USER"),"");
    }
    private void migrate(String url,String user,String password) throws Exception {
        String schema="posting_test_"+UUID.randomUUID().toString().replace("-","");
        Flyway old=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("3").load();old.migrate();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("SET SCHEMA '"+schema+"'");
            String owner=UUID.randomUUID().toString(),app=UUID.randomUUID().toString(),stage=UUID.randomUUID().toString();
            statement.execute("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES ('"+owner+"','old@example.test','Old User','synthetic-hash',CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO applications(id,owner_id,company,role,location,status,notes,deleted,version,created_at,updated_at) VALUES ('"+app+"','"+owner+"','Legacy Labs','Intern','Remote','SAVED','Existing notes',TRUE,7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO interview_stages(id,application_id,position,name,state) VALUES ('"+stage+"','"+app+"',0,'Recruiter screen','PLANNED')");
            statement.execute("INSERT INTO reminder_preferences(owner_id,enabled) VALUES ('"+owner+"',TRUE)");
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
            try(var rows=statement.executeQuery("SELECT company,notes,deleted,version,recruiter_name,compensation_min,compensation_status,posting_description_revision FROM applications")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("Legacy Labs");assertThat(rows.getString(2)).isEqualTo("Existing notes");
                assertThat(rows.getBoolean(3)).isTrue();assertThat(rows.getLong(4)).isEqualTo(7);assertThat(rows.getString(5)).isEmpty();
                assertThat(rows.getBigDecimal(6)).isNull();assertThat(rows.getString(7)).isEqualTo("UNKNOWN");assertThat(rows.getInt(8)).isZero();
            }
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM interview_stages")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
            try(var rows=statement.executeQuery("SELECT enabled FROM reminder_preferences")){rows.next();assertThat(rows.getBoolean(1)).isTrue();}
        }
    }
}
