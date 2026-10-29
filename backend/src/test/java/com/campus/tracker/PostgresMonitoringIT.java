package com.campus.tracker;

import com.campus.tracker.migration.MigrationMain;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Runs the operator's actual psql report with column-limited, read-only access. */
class PostgresMonitoringIT {
 @Test void privateReportFindsBacklogsAndMissingExportEvidenceWithoutReadingSecrets() throws Exception {
  try(var db=new PostgresPrivilegesIT.TestDatabase(false)) {
   var provision=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","provision.sql");
   assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator","MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) {
    // The report needs identity metadata for eligibility joins, never credentials,
    // encryption material, audit subjects, correlation ids or arbitrary payloads.
    s.execute("CREATE ROLE campus_monitor NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
    s.execute("GRANT USAGE ON SCHEMA public TO campus_monitor");
    s.execute("GRANT SELECT(id,enabled,operator_intake_paused,health_intake_paused) ON password_reset_control TO campus_monitor");
    s.execute("GRANT SELECT(id,created_at,expires_at,status,lease_expires_at,completed_at,failure_category) ON password_reset_requests TO campus_monitor");
    s.execute("GRANT SELECT(id,request_id,user_id,email,credential_version,expires_at,consumed_at,revoked_at) ON password_reset_tokens TO campus_monitor");
    s.execute("GRANT SELECT(id,email,credential_version) ON campus_users TO campus_monitor");
    s.execute("GRANT SELECT(message_type,status,created_at,password_reset_token_id,failure_category,lease_expires_at,next_attempt_at) ON email_outbox TO campus_monitor");
    s.execute("GRANT SELECT(occurred_at) ON auth_security_events TO campus_monitor");
   }
   var empty=report(db,null);
   assertThat(empty.get("control_row_missing")).isEqualByComparingTo("0");
   assertThat(empty.get("audit_checkpoint_missing")).isEqualByComparingTo("1");
   assertThat(empty.get("audit_export_lag_seconds")).isNull();
   for(String metric:List.of("intake_pending","intake_oldest_seconds","eligible_link_pending","eligible_link_oldest_seconds","notice_pending","audit_unexported_events"))
    assertThat(empty.get(metric)).as(metric).isEqualByComparingTo("0");

   String exportedThrough;
   try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) {
    seed(s);
    try(var r=s.executeQuery("SELECT CURRENT_TIMESTAMP - INTERVAL '1 hour'")) {assertThat(r.next()).isTrue();exportedThrough=r.getString(1);}
   }
   var waiting=report(db,null);
   assertThat(waiting.get("health_intake_paused")).isEqualByComparingTo("1");
   assertThat(waiting.get("intake_pending")).isEqualByComparingTo("1");
   assertThat(waiting.get("intake_oldest_seconds")).isGreaterThanOrEqualTo(new BigDecimal("180"));
   assertThat(waiting.get("intake_deadline_within_60s")).isEqualByComparingTo("1");
   // DONE intake must not hide undelivered eligible mail; the consumed notice
   // token's pending link is deliberately excluded from the eligible-link count.
   assertThat(waiting.get("eligible_link_pending")).isEqualByComparingTo("1");
   assertThat(waiting.get("eligible_link_oldest_seconds")).isGreaterThanOrEqualTo(new BigDecimal("180"));
   assertThat(waiting.get("eligible_link_deadline_within_120s")).isEqualByComparingTo("1");
   assertThat(waiting.get("notice_pending")).isEqualByComparingTo("1");
   assertThat(waiting.get("notice_deadline_within_1h")).isEqualByComparingTo("1");
   assertThat(waiting.get("audit_unexported_events")).isEqualByComparingTo("1");
   var exported=report(db,exportedThrough);
   assertThat(exported.get("audit_checkpoint_missing")).isEqualByComparingTo("0");
   assertThat(exported.get("audit_checkpoint_in_future")).isEqualByComparingTo("0");
   assertThat(exported.get("audit_export_lag_seconds")).isGreaterThanOrEqualTo(new BigDecimal("3600"));
   assertThat(exported.get("audit_unexported_events")).isEqualByComparingTo("0");
   var future=report(db,"2999-01-01T00:00:00Z");
   assertThat(future.get("audit_checkpoint_in_future")).isEqualByComparingTo("1");
   assertThat(future.get("audit_export_lag_seconds")).isNegative();

   try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) {
    s.execute("SET ROLE campus_monitor");
    for(String denied:List.of("SELECT payload FROM password_reset_requests","SELECT payload FROM email_outbox",
       "SELECT key_id,nonce FROM email_outbox","SELECT password_hash FROM campus_users","SELECT account_subject FROM auth_security_events",
       "SELECT token_hash FROM password_reset_tokens","UPDATE password_reset_control SET health_intake_paused=false"))
     assertThatThrownBy(()->s.execute(denied)).as(denied).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
   }
  }
 }

 private Map<String,BigDecimal> report(PostgresPrivilegesIT.TestDatabase db,String checkpoint) throws Exception {
  var args=new ArrayList<>(List.of("psql","-X","-q","-A","-t","-F","|","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus"));
  if(checkpoint!=null) args.addAll(List.of("-v","audit_exported_through="+checkpoint));
  args.addAll(List.of("-c","SET ROLE campus_monitor","-f","../monitoring/password-reset-health.sql"));
  var result=db.psql(args.toArray(String[]::new));
  assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
  String output=result.getStderr();
  assertThat(output).doesNotContain("monitor-secret@example.test","encrypted-monitor-payload","private-monitor-hash","subject-must-stay-private","00000000-0000-0000-0000-000000000001");
  var metrics=new LinkedHashMap<String,BigDecimal>();
  for(String line:output.split("\\R")) {
   String[] pair=line.split("\\|",-1);
   if(pair.length!=2) continue;
   assertThat(pair[0]).matches("[a-z_]+(?:[0-9]+[a-z_]*)?");
   assertThat(metrics).doesNotContainKey(pair[0]);
   metrics.put(pair[0],pair[1].isEmpty()?null:new BigDecimal(pair[1]));
  }
  assertThat(metrics).containsKeys("control_row_missing","intake_pending","eligible_link_pending","audit_checkpoint_missing","audit_export_lag_seconds");
  return metrics;
 }

 private void seed(Statement s) throws SQLException {
  s.execute("UPDATE password_reset_control SET enabled=true,health_intake_paused=true WHERE id=1");
  s.execute("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES ('00000000-0000-0000-0000-000000000001','monitor-secret@example.test','Synthetic monitor','private-monitor-hash',CURRENT_TIMESTAMP)");
  s.execute("""
      INSERT INTO password_reset_requests(id,request_order,payload,key_id,nonce,status,next_attempt_at,created_at,expires_at) VALUES
      ('00000000-0000-0000-0000-000000000010',nextval('auth_security_event_order'),'encrypted-monitor-payload','test','nonce','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP-INTERVAL '3 minutes',CURRENT_TIMESTAMP+INTERVAL '30 seconds')
      """);
  s.execute("""
      INSERT INTO password_reset_requests(id,request_order,status,next_attempt_at,created_at,expires_at,completed_at) VALUES
      ('00000000-0000-0000-0000-000000000020',nextval('auth_security_event_order'),'DONE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP-INTERVAL '3 minutes',CURRENT_TIMESTAMP+INTERVAL '7 minutes',CURRENT_TIMESTAMP),
      ('00000000-0000-0000-0000-000000000030',nextval('auth_security_event_order'),'DONE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP-INTERVAL '24 hours',CURRENT_TIMESTAMP-INTERVAL '23 hours',CURRENT_TIMESTAMP-INTERVAL '23 hours')
      """);
  s.execute("""
      INSERT INTO password_reset_tokens(id,request_id,user_id,email,token_hash,credential_version,created_at,expires_at,consumed_at) VALUES
      ('00000000-0000-0000-0000-000000000021','00000000-0000-0000-0000-000000000020','00000000-0000-0000-0000-000000000001','monitor-secret@example.test','link-digest',0,CURRENT_TIMESTAMP-INTERVAL '3 minutes',CURRENT_TIMESTAMP+INTERVAL '1 minute',NULL),
      ('00000000-0000-0000-0000-000000000031','00000000-0000-0000-0000-000000000030','00000000-0000-0000-0000-000000000001','monitor-secret@example.test','notice-digest',0,CURRENT_TIMESTAMP-INTERVAL '24 hours',CURRENT_TIMESTAMP+INTERVAL '1 minute',CURRENT_TIMESTAMP-INTERVAL '23 hours')
      """);
  s.execute("""
      INSERT INTO email_outbox(id,message_type,password_reset_token_id,payload,key_id,nonce,status,next_attempt_at,created_at) VALUES
      ('00000000-0000-0000-0000-000000000022','PASSWORD_RESET_LINK','00000000-0000-0000-0000-000000000021','encrypted-monitor-payload','test','nonce','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP-INTERVAL '3 minutes'),
      ('00000000-0000-0000-0000-000000000032','PASSWORD_CHANGED','00000000-0000-0000-0000-000000000031','encrypted-monitor-payload','test','nonce','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP-INTERVAL '23 hours 30 minutes'),
      ('00000000-0000-0000-0000-000000000033','PASSWORD_RESET_LINK','00000000-0000-0000-0000-000000000031','encrypted-monitor-payload','test','nonce','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP-INTERVAL '23 hours 30 minutes')
      """);
  s.execute("""
      INSERT INTO auth_security_events(id,completion_id,event_type,occurred_at,account_subject,correlation_id) VALUES
      ('00000000-0000-0000-0000-000000000041','00000000-0000-0000-0000-000000000031','PASSWORD_RESET_COMPLETED',CURRENT_TIMESTAMP-INTERVAL '23 hours','subject-must-stay-private','00000000-0000-0000-0000-000000000042')
      """);
 }
}
