package com.campus.tracker;

import com.campus.tracker.migration.MigrationMain;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import java.nio.file.Path;
import java.sql.*;
import java.util.Map;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import com.campus.tracker.application.importing.*;
import static com.campus.tracker.application.importing.ImportDtos.*;
import static org.assertj.core.api.Assertions.*;

class PostgresPrivilegesIT {
 @Test void freshDatabaseHasOnlyRequiredRuntimePrivileges() throws Exception { verify(false); }
 @Test void legacyUpgradePreservesRecordsAndMigrationHistory() throws Exception { verify(true); }
 @Test void existingV3ProvisioningAddsSeparateRetentionIdentityBeforeRecoveryMigration() throws Exception { verifyRetentionUpgrade("3"); }
 @Test void existingV4ProvisioningAddsSeparateRetentionIdentityBeforeRecoveryMigration() throws Exception { verifyRetentionUpgrade("4"); }
 private void verifyRetentionUpgrade(String baseline) throws Exception {
  try(var db=new TestDatabase(false)) {
   var provision=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","provision.sql");
   assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
   Flyway.configure().dataSource(db.getJdbcUrl(),"campus_migrator","migration-'quote-password").target(baseline).load().migrate();
   try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) {
    s.execute("REVOKE CONNECT ON DATABASE campus FROM campus_retention"); s.execute("DROP ROLE campus_retention");
   }
   for(int i=0;i<2;i++) {
    var retention=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","provision-retention.sql");
    assertThat(retention.getExitCode()).withFailMessage(retention.getStderr()).isZero();
   }
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator","MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   var verification=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","verify-runtime.sql");
   assertThat(verification.getExitCode()).withFailMessage(verification.getStderr()).isZero();
  }
 }
 @Test void verifierRejectsEveryMissingRequiredDmlPrivilege() throws Exception {
  try(var db=new TestDatabase(false)) {
   var provision=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","provision.sql");
   assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator","MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   var baseline=verifyRuntime(db);
   assertThat(baseline.getExitCode()).withFailMessage(baseline.getStderr()).isZero();
   // Revoke one grant at a time: a verifier must reject even a single missing permission.
   String[][] required={
       {"campus_users","SELECT","INSERT","UPDATE (theme_preference)","UPDATE (email_verified_at)","UPDATE (password_hash)","UPDATE (credential_version)","UPDATE (reset_request_cutoff)"},
       {"applications","SELECT","INSERT","UPDATE"},
       {"interview_stages","SELECT","INSERT","UPDATE","DELETE"},
       {"application_imports","SELECT","INSERT","UPDATE","DELETE"},
       {"application_import_quota_locks","SELECT","INSERT","UPDATE","DELETE"},
       {"application_import_rates","SELECT","INSERT","UPDATE","DELETE"},
       {"email_verification_tokens","SELECT","INSERT","UPDATE","DELETE"},
       {"email_outbox","SELECT","INSERT","UPDATE","DELETE"},
       {"auth_rate_limits","SELECT","INSERT","UPDATE","DELETE"},
       {"password_reset_requests","SELECT","INSERT","UPDATE","DELETE"},
       {"password_reset_tokens","SELECT","INSERT","UPDATE","DELETE"}
   };
   var softly=new org.assertj.core.api.SoftAssertions();
   try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) {
    for(String[] table:required) for(int i=1;i<table.length;i++) {
     String privilege=table[i], target=" ON TABLE public."+table[0]+" TO campus_runtime";
     s.execute("REVOKE "+privilege+" ON TABLE public."+table[0]+" FROM campus_runtime");
     try {
      var missing=verifyRuntime(db);
      softly.assertThat(missing.getExitCode()).as("verifier rejects missing %s on %s: %s",privilege,table[0],missing.getStderr()).isNotZero();
      softly.assertThat(missing.getStderr()).as("missing %s on %s reports a privilege failure",privilege,table[0]).contains("Missing");
     } finally {s.execute("GRANT "+privilege+target);}
    }
   }
   var restored=verifyRuntime(db);
   assertThat(restored.getExitCode()).withFailMessage(restored.getStderr()).isZero();
   softly.assertAll();
  }
 }
 private Result verifyRuntime(TestDatabase db) throws Exception {
  return db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","verify-runtime.sql");
 }
 private void verify(boolean legacy) throws Exception {
  try (var db = new TestDatabase(legacy)) {
   if (legacy) {
    Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("1").load().migrate();
    try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) {
     s.execute("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES ('00000000-0000-0000-0000-000000000001','legacy@example.test','Legacy','hash',CURRENT_TIMESTAMP)");
    }
   }
   var provision=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f",""+(legacy?"upgrade-existing.sql":"provision.sql"));
   assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
   // Preserve the V1 ownership-inventory guard, then prove the import migration from an existing V3 schema.
   if(legacy) Flyway.configure().dataSource(db.getJdbcUrl(),"campus_migrator","migration-'quote-password").target("3").load().migrate();
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator","MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   try(var c=DriverManager.getConnection(db.getJdbcUrl(),"campus_migrator","migration-'quote-password");var statement=c.createStatement();
       var versions=statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank")) {
    var applied=new ArrayList<String>();while(versions.next()) applied.add(versions.getString(1));
    assertThat(applied).containsExactly("1","2","3","4","5");
   }
   // Repeat deployment: migrations and grants are idempotent.
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator","MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   try(var c=DriverManager.getConnection(db.getJdbcUrl(),"campus_runtime","runtime-'quote-password");var s=c.createStatement()) {
    try(var version=s.executeQuery("select version()")) {version.next();System.out.println("Isolated import verification database: "+version.getString(1));}
    if(legacy) {try(var r=s.executeQuery("SELECT display_name FROM campus_users")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("Legacy");}}
    s.execute("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES ('00000000-0000-0000-0000-000000000002','new@example.test','New','hash',CURRENT_TIMESTAMP)");
    s.execute("UPDATE campus_users SET theme_preference='dark',email_verified_at=CURRENT_TIMESTAMP");
    s.execute("UPDATE campus_users SET password_hash='reset-hash',credential_version=credential_version+1,reset_request_cutoff=nextval('auth_security_event_order')");
    assertThat(s.executeUpdate("UPDATE password_reset_control SET health_intake_paused=true WHERE id=1")).isEqualTo(1);
    s.execute("UPDATE password_reset_control SET health_intake_paused=false WHERE id=1");
    s.execute("INSERT INTO password_reset_requests(id,request_order,payload,key_id,nonce,status,next_attempt_at,created_at,expires_at) VALUES "
      +"('00000000-0000-0000-0000-000000000040',nextval('auth_security_event_order'),'encrypted','test','nonce','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '10 minutes')");
    s.execute("INSERT INTO password_reset_tokens(id,request_id,user_id,email,token_hash,credential_version,created_at,expires_at) VALUES "
      +"('00000000-0000-0000-0000-000000000041','00000000-0000-0000-0000-000000000040','00000000-0000-0000-0000-000000000002','new@example.test','"+"a".repeat(64)+"',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '30 minutes')");
    s.execute("UPDATE password_reset_tokens SET revoked_at=CURRENT_TIMESTAMP WHERE id='00000000-0000-0000-0000-000000000041'");
    assertThat(s.executeUpdate("DELETE FROM password_reset_tokens WHERE id='00000000-0000-0000-0000-000000000041'")).isEqualTo(1);
    s.execute("UPDATE password_reset_requests SET status='DONE',payload=NULL,key_id=NULL,nonce=NULL,completed_at=CURRENT_TIMESTAMP WHERE id='00000000-0000-0000-0000-000000000040'");
    assertThat(s.executeUpdate("DELETE FROM password_reset_requests WHERE id='00000000-0000-0000-0000-000000000040'")).isEqualTo(1);
    s.execute("INSERT INTO auth_security_events(id,completion_id,event_type,occurred_at,account_subject,correlation_id) VALUES "
      +"('00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000021','PASSWORD_RESET_COMPLETED',CURRENT_TIMESTAMP-INTERVAL '31 days','v1:subject','00000000-0000-0000-0000-000000000031'),"
      +"('00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000022','PASSWORD_RESET_COMPLETED',CURRENT_TIMESTAMP,'v1:subject','00000000-0000-0000-0000-000000000032')");
    s.execute("INSERT INTO applications(id,owner_id,company,role,location,status,notes,created_at,updated_at) VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','Company','Role','Remote','SAVED','',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    s.execute("UPDATE applications SET deleted=TRUE,version=version+1");
    s.execute("UPDATE applications SET deleted=FALSE,version=version+1");
    s.execute("INSERT INTO interview_stages VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003',0,'Interview',NULL,'PLANNED')");
    s.execute("UPDATE interview_stages SET name='Changed'");
    assertThat(s.executeUpdate("DELETE FROM interview_stages")).isEqualTo(1);
    for(String denied : new String[]{"CREATE TABLE public.forbidden_probe(id int)","CREATE TEMP TABLE forbidden_temp(id int)","CREATE SCHEMA forbidden_schema","CREATE ROLE forbidden_role","CREATE DATABASE forbidden_db","DROP TABLE applications","ALTER TABLE applications ADD COLUMN forbidden text","TRUNCATE applications","DELETE FROM applications","DELETE FROM campus_users","UPDATE campus_users SET display_name='forbidden'","UPDATE campus_users SET email='forbidden@example.test'","UPDATE campus_users SET email_verification_exempt=TRUE","UPDATE auth_security_events SET account_subject='forbidden'","DELETE FROM auth_security_events","UPDATE password_reset_control SET enabled=true","UPDATE password_reset_control SET operator_intake_paused=true","SELECT setval('auth_security_event_order',1)","ALTER SEQUENCE auth_security_event_order CACHE 100","SELECT * FROM flyway_schema_history","SET ROLE campus_retention","SET ROLE campus_migrator","SET ROLE "+db.getUsername()}) {
     assertThatThrownBy(()->s.execute(denied)).as(denied).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
    }
   }
   try(var c=DriverManager.getConnection(db.getJdbcUrl(),"campus_retention","retention-'quote-password");var s=c.createStatement()) {
    assertThat(s.executeUpdate("DELETE FROM auth_security_events WHERE occurred_at<CURRENT_TIMESTAMP-INTERVAL '30 days'")).isEqualTo(1);
    try(var rows=s.executeQuery("SELECT id,occurred_at FROM auth_security_events")) {assertThat(rows.next()).isTrue();assertThat(rows.getObject("id").toString()).isEqualTo("00000000-0000-0000-0000-000000000012");assertThat(rows.next()).isFalse();}
    for(String denied:new String[]{"SELECT account_subject FROM auth_security_events","UPDATE auth_security_events SET occurred_at=CURRENT_TIMESTAMP","SELECT * FROM campus_users","SET ROLE campus_runtime","SET ROLE campus_migrator"}) {
     assertThatThrownBy(()->s.execute(denied)).as(denied).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
    }
   }
   verifyHttp(db);
   var verification=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","verify-runtime.sql");
   assertThat(verification.getExitCode()).withFailMessage(verification.getStderr()).isZero();
  }
 }
 private void verifyHttp(TestDatabase db) throws Exception {
  try(var context=startContext(db.getJdbcUrl())) {
   var server=(org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context;
   verifyConcurrentAuth(context);
   String base="http://127.0.0.1:"+server.getWebServer().getPort();
   verifyHttpSession(db,context,base);
  }
 }
 static org.springframework.context.ConfigurableApplicationContext startContext(String jdbcUrl) {
  return new org.springframework.boot.builder.SpringApplicationBuilder(CampusApplication.class)
      .profiles("prod").run(
          "--server.port=0","--server.address=127.0.0.1",
          "--spring.datasource.url="+jdbcUrl,
          "--spring.datasource.username=campus_runtime",
          "--spring.datasource.password=runtime-'quote-password",
          "--campus.registration-enabled=true","--server.servlet.session.cookie.secure=false",
          "--spring.mail.host=127.0.0.1", "--spring.mail.port=1", "--spring.mail.username=", "--spring.mail.password=",
          "--campus.mail.public-origin=http://localhost", "--campus.mail.sender=capture@example.test",
          "--campus.mail.key-id=isolated-test", "--campus.mail.encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
          "--campus.mail.production=false", "--campus.mail.worker-enabled=false", "--campus.reset.health-enabled=false",
          "--campus.reset.audit-secret=isolated-postgres-audit-secret-long-enough", "--campus.reset.audit-key-id=test-audit",
          "--campus.mail.help-destination=http://localhost",
          "--campus.imports.cleanup-enabled=false",
          "--campus.verification.rate-limit-secret=isolated-postgres-verification-secret",
          "--debug=false","--logging.level.root=WARN");
 }
 private void verifyHttpSession(TestDatabase db,org.springframework.context.ConfigurableApplicationContext context,String base) throws Exception {
   var cookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
   var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).version(java.net.http.HttpClient.Version.HTTP_1_1).build();
   var json=new com.fasterxml.jackson.databind.ObjectMapper();
   var csrf=json.readTree(http(client,base,"GET","/api/auth/csrf",null,null,200).body());
   String token=csrf.get("token").asText();
   String email="http-"+java.util.UUID.randomUUID()+"@example.test";
   http(client,base,"POST","/api/auth/register",json.writeValueAsString(Map.of(
       "displayName","Runtime Test","email",email,"password","Campus-test-123!","includeSamples",true)),token,202);
   http(client,base,"POST","/api/auth/login","email="+java.net.URLEncoder.encode(email,java.nio.charset.StandardCharsets.UTF_8)
       +"&password=Campus-test-123%21",token,403);
   var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
   var outbox=jdbc.queryForMap("SELECT id,payload,nonce,key_id FROM email_outbox WHERE verification_token_id IN "
       +"(SELECT id FROM email_verification_tokens WHERE email=?)",email);
   var payload=context.getBean(com.campus.tracker.mail.OutboxCrypto.class).decrypt(
       (java.util.UUID)outbox.get("id"),com.campus.tracker.mail.EmailMessage.Type.EMAIL_VERIFICATION,
       (String)outbox.get("payload"),(String)outbox.get("nonce"),(String)outbox.get("key_id"));
   http(client,base,"POST","/api/auth/email-verification/confirm",json.writeValueAsString(Map.of(
       "token",payload.token(),"password","Campus-test-123!")),token,200);
   http(client,base,"POST","/api/auth/login","email="+java.net.URLEncoder.encode(email,java.nio.charset.StandardCharsets.UTF_8)
       +"&password=Campus-test-123%21",token,200);
   token=json.readTree(http(client,base,"GET","/api/auth/csrf",null,null,200).body()).get("token").asText();
   http(client,base,"PUT","/api/me/preferences",json.writeValueAsString(Map.of("themePreference","dark")),token,200);
   context.getBean(com.campus.tracker.mail.EmailOutboxWorker.class).deliverPending();
   var listed=json.readTree(http(client,base,"GET","/api/applications",null,null,200).body());
   assertThat(listed.get("totalElements").asInt()).isEqualTo(10);
   var draft=json.createObjectNode().put("company","Runtime Company").put("role","Intern")
       .put("location","Remote").put("status","Saved").put("notes","Privilege test");
   draft.putArray("stages").addObject().put("name","Interview").put("state","Planned");
   var created=json.readTree(http(client,base,"POST","/api/applications",json.writeValueAsString(draft),token,201).body());
   String path="/api/applications/"+created.get("id").asText();
   draft.putArray("stages"); draft.put("company","Updated Runtime Company");
   var updated=json.readTree(httpVersion(client,base,"PUT",path,json.writeValueAsString(draft),token,
       created.get("version").asText(),200).body());
   assertThat(updated.get("stages").size()).isZero();
   var deleted=json.readTree(httpVersion(client,base,"DELETE",path,null,token,updated.get("version").asText(),200).body());
   var restored=json.readTree(httpVersion(client,base,"POST",path+"/restore",null,token,deleted.get("version").asText(),200).body());
   assertThat(restored.get("deleted").asBoolean()).isFalse();
   verifyPasswordRecovery(db,context,client,base,email);
   token=json.readTree(http(client,base,"GET","/api/auth/csrf",null,null,200).body()).get("token").asText();
   http(client,base,"GET","/api/auth/me",null,null,200);
   verifyImportHttp(client,base,token,json,restored);
   verifyImportConcurrencyAndExpiry(context);
   verifyImportMaximumBatch(context);
   http(client,base,"POST","/api/auth/logout",null,token,204);
 }
 private void verifyPasswordRecovery(TestDatabase db,org.springframework.context.ConfigurableApplicationContext context,
      java.net.http.HttpClient oldSession,String base,String email) throws Exception {
  try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) {
   assertThat(s.executeUpdate("UPDATE password_reset_control SET enabled=true WHERE id=1")).isEqualTo(1);
  }
  var json=new com.fasterxml.jackson.databind.ObjectMapper();
  var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
  var beforeApps=json.readTree(http(oldSession,base,"GET","/api/applications",null,null,200).body());
  var beforePreferences=json.readTree(http(oldSession,base,"GET","/api/me/preferences",null,null,200).body());
  var unchanged=jdbc.queryForMap("SELECT id,email,display_name,created_at,theme_preference,email_verified_at,email_verification_exempt FROM campus_users WHERE email=?",email);
  long oldVersion=jdbc.queryForObject("SELECT credential_version FROM campus_users WHERE email=?",Long.class,email);
  var recovery=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).build();
  String csrf=json.readTree(http(recovery,base,"GET","/api/auth/csrf",null,null,200).body()).get("token").asText();
  var requested=json.readTree(http(recovery,base,"POST","/api/auth/password-reset/request",json.writeValueAsString(Map.of("email",email)),csrf,202).body());
  assertThat(requested.get("status").asText()).isEqualTo("PASSWORD_RESET_REQUEST_ACCEPTED");
  context.getBean(com.campus.tracker.auth.reset.PasswordResetRequestWorker.class).processPending();
  var job=jdbc.queryForMap("SELECT o.id,o.payload,o.nonce,o.key_id,o.password_reset_token_id FROM email_outbox o "
      +"JOIN password_reset_tokens t ON t.id=o.password_reset_token_id WHERE t.email=? AND o.message_type='PASSWORD_RESET_LINK'",email);
  verifyResetLinkHealth(context,(java.util.UUID)job.get("id"));
  var message=context.getBean(com.campus.tracker.mail.OutboxCrypto.class).decrypt((java.util.UUID)job.get("id"),
      com.campus.tracker.mail.EmailMessage.Type.PASSWORD_RESET_LINK,(String)job.get("payload"),(String)job.get("nonce"),(String)job.get("key_id"));
  assertThat(message.recipient()).isEqualTo(email);
  var completion=json.readTree(http(recovery,base,"POST","/api/auth/password-reset/confirm",json.writeValueAsString(Map.of(
      "token",message.token(),"newPassword","Recovered campus passphrase 9284")),csrf,200).body());
  assertThat(completion.get("status").asText()).isEqualTo("PASSWORD_RESET_COMPLETE");
  http(recovery,base,"GET","/api/auth/me",null,null,401);
  var stale=http(oldSession,base,"GET","/api/auth/me",null,null,401);
  assertThat(stale.headers().allValues("Set-Cookie")).isEmpty();
  // Epoch revocation must precede import admission and even its missing-CSRF-header rejection.
  var staleImport=http(oldSession,base,"GET","/api/application-imports/"+java.util.UUID.randomUUID(),null,null,401);
  assertThat(staleImport.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
  var staleUpload=http(oldSession,base,"POST","/api/application-imports/preview","unread",null,401);
  assertThat(staleUpload.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
  String newCsrf=json.readTree(http(oldSession,base,"GET","/api/auth/csrf",null,null,200).body()).get("token").asText();
  String encodedEmail=java.net.URLEncoder.encode(email,java.nio.charset.StandardCharsets.UTF_8);
  http(oldSession,base,"POST","/api/auth/login","email="+encodedEmail+"&password=Campus-test-123%21",newCsrf,401);
  http(oldSession,base,"POST","/api/auth/login","email="+encodedEmail+"&password="
      +java.net.URLEncoder.encode("Recovered campus passphrase 9284",java.nio.charset.StandardCharsets.UTF_8),newCsrf,200);
  assertThat(json.readTree(http(oldSession,base,"GET","/api/applications",null,null,200).body())).isEqualTo(beforeApps);
  assertThat(json.readTree(http(oldSession,base,"GET","/api/me/preferences",null,null,200).body())).isEqualTo(beforePreferences);
  assertThat(jdbc.queryForMap("SELECT id,email,display_name,created_at,theme_preference,email_verified_at,email_verification_exempt FROM campus_users WHERE email=?",email)).isEqualTo(unchanged);
  assertThat(jdbc.queryForObject("SELECT credential_version FROM campus_users WHERE email=?",Long.class,email)).isEqualTo(oldVersion+1);
  var replay=json.readTree(http(recovery,base,"POST","/api/auth/password-reset/confirm",json.writeValueAsString(Map.of(
      "token",message.token(),"newPassword","Another campus passphrase 5742")),csrf,400).body());
  assertThat(replay.get("code").asText()).isEqualTo("PASSWORD_RESET_INVALID");
  Object challenge=job.get("password_reset_token_id");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE id=? AND consumed_at IS NOT NULL AND revoked_at IS NULL",Long.class,challenge)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_security_events WHERE completion_id=?",Long.class,challenge)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE password_reset_token_id=? AND message_type='PASSWORD_CHANGED'",Long.class,challenge)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE id=? AND status='CANCELLED' AND payload IS NULL AND key_id IS NULL AND nonce IS NULL",Long.class,job.get("id"))).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_requests r JOIN password_reset_tokens t ON t.request_id=r.id WHERE t.id=? AND r.status='DONE' AND r.payload IS NULL AND r.key_id IS NULL AND r.nonce IS NULL",Long.class,challenge)).isEqualTo(1);
  verifyExpiryAfterAccountLockWait(db,context,email);
 }
 private void verifyResetLinkHealth(org.springframework.context.ConfigurableApplicationContext context,java.util.UUID jobId) {
  var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
  var health=new com.campus.tracker.auth.reset.ResetHealth(jdbc,
      context.getBean(com.campus.tracker.auth.reset.DatabaseTime.class),
      context.getBean(com.campus.tracker.auth.reset.PasswordResetRequestWorker.class),
      context.getBean(com.campus.tracker.auth.reset.ResetMaintenance.class),
      context.getBean(com.campus.tracker.auth.reset.RecoveryCapacity.class),true);
  health.check();
  assertThat(jdbc.queryForObject("SELECT health_intake_paused FROM password_reset_control WHERE id=1",Boolean.class)).isFalse();
  assertThat(jdbc.update("UPDATE email_outbox SET created_at=created_at-INTERVAL '3 minutes' WHERE id=? AND message_type='PASSWORD_RESET_LINK'",jobId)).isEqualTo(1);
  health.check();
  assertThat(jdbc.queryForObject("SELECT health_intake_paused FROM password_reset_control WHERE id=1",Boolean.class)).isTrue();
  assertThat(jdbc.update("UPDATE email_outbox SET created_at=created_at+INTERVAL '3 minutes' WHERE id=? AND message_type='PASSWORD_RESET_LINK'",jobId)).isEqualTo(1);
  health.check();
  assertThat(jdbc.queryForObject("SELECT health_intake_paused FROM password_reset_control WHERE id=1",Boolean.class)).isFalse();
 }
 private void verifyExpiryAfterAccountLockWait(TestDatabase db,org.springframework.context.ConfigurableApplicationContext context,String email) throws Exception {
  var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
  var user=jdbc.queryForMap("SELECT id,password_hash,credential_version FROM campus_users WHERE email=?",email);
  java.util.UUID intake=java.util.UUID.randomUUID(),challenge=java.util.UUID.randomUUID();
  byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
  String token=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  jdbc.update("INSERT INTO password_reset_requests(id,request_order,status,attempts,next_attempt_at,created_at,expires_at,completed_at) VALUES(?,nextval('auth_security_event_order'),'DONE',1,clock_timestamp(),clock_timestamp(),clock_timestamp()+INTERVAL '10 minutes',clock_timestamp())",intake);
  jdbc.update("INSERT INTO password_reset_tokens(id,request_id,user_id,email,token_hash,credential_version,created_at,expires_at) VALUES(?,?,?,?,?,?,clock_timestamp(),clock_timestamp()+INTERVAL '30 minutes')",challenge,intake,user.get("id"),email,digest,user.get("credential_version"));
  var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
  try(var blocker=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());
      var observer=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())) {
   blocker.setAutoCommit(false);
   int blockingPid;
   try(var s=blocker.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")) {assertThat(r.next()).isTrue();blockingPid=r.getInt(1);}
   try(var s=blocker.prepareStatement("SELECT id FROM campus_users WHERE id=? FOR UPDATE")) {s.setObject(1,user.get("id"));try(var r=s.executeQuery()){assertThat(r.next()).isTrue();}}
   var started=new java.util.concurrent.CountDownLatch(1);
   var result=executor.submit(()->{
    started.countDown();
    try {context.getBean(com.campus.tracker.auth.reset.PasswordResetService.class).confirm(token,"Expired campus passphrase 3948","192.0.2.181");return 200;}
    catch(com.campus.tracker.common.ApiException invalid) {return invalid.getStatus().value();}
   });
   assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   Timestamp transactionStarted=null;
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
   try(var s=observer.prepareStatement("SELECT xact_start FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))")) {
    s.setInt(1,blockingPid);
    while(transactionStarted==null && System.nanoTime()<deadline && !result.isDone()) {
     try(var r=s.executeQuery()) {if(r.next()) transactionStarted=r.getTimestamp(1);}
     if(transactionStarted==null) Thread.sleep(10);
    }
   }
   assertThat(transactionStarted).as("Confirmation must reach the account lock before testing expiry").isNotNull();
   try(var s=observer.prepareStatement("UPDATE password_reset_tokens SET expires_at=clock_timestamp()+INTERVAL '100 milliseconds' WHERE id=? RETURNING expires_at")) {
    s.setObject(1,challenge);try(var r=s.executeQuery()){assertThat(r.next()).isTrue();assertThat(r.getTimestamp(1).toInstant()).isAfter(transactionStarted.toInstant());}
   }
   // The observed PostgreSQL lock is the barrier; a short primary-clock wait crosses expiry.
   try(var s=observer.createStatement()) {s.execute("SELECT pg_sleep(0.2)");}
   try(var s=observer.prepareStatement("SELECT expires_at<clock_timestamp() FROM password_reset_tokens WHERE id=?")) {
    s.setObject(1,challenge);try(var r=s.executeQuery()){assertThat(r.next()).isTrue();assertThat(r.getBoolean(1)).isTrue();}
   }
   blocker.rollback();
   assertThat(result.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(400);
   assertThat(jdbc.queryForMap("SELECT id,password_hash,credential_version FROM campus_users WHERE email=?",email)).isEqualTo(user);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE id=? AND consumed_at IS NULL",Long.class,challenge)).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_security_events WHERE completion_id=?",Long.class,challenge)).isZero();
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE password_reset_token_id=? AND message_type='PASSWORD_CHANGED'",Long.class,challenge)).isZero();
  } finally {executor.shutdownNow();}
 }
 private void verifyImportConcurrencyAndExpiry(org.springframework.context.ConfigurableApplicationContext context) throws Exception {
   var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
   var previews=context.getBean(ImportPreviewService.class);
   var commits=context.getBean(ImportCommitService.class);
   var statuses=context.getBean(ImportStatusService.class);
   var cleanup=context.getBean(ImportCleanupWorker.class);
   var manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
   var json=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
   String options=json.writeValueAsString(new Options(1,Map.of("0","company","1","role"),"ISO",List.of(),null));
   byte[] file="Company,Role\nPG concurrency,Intern\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   UUID owner=importAccount(jdbc);
   Preview preview=previews.create(owner,file,options);
   var request=new CommitRequest(preview.reviewDigest(),new Acknowledgement(0));
   List<Object> duplicates=importRace(()->commits.commit(owner,preview.id(),request),()->commits.commit(owner,preview.id(),request));
   Receipt original=statuses.status(owner,preview.id()).receipt();
   assertThat(original).isNotNull();
   for(Object outcome:duplicates) if(outcome instanceof Receipt receipt) assertThat(receipt).isEqualTo(original);else assertThat(outcome).isInstanceOf(org.springframework.dao.DataAccessException.class);
   assertThat(jdbc.queryForObject("select count(*) from applications where owner_id=?",Long.class,owner)).isEqualTo(1);
   assertThat(commits.commit(owner,preview.id(),request)).isEqualTo(original);

   UUID applicationId=original.rows().get(0).applicationId();
   String updateOptions=json.writeValueAsString(new Options(1,Map.of("0","applicationId","1","notes"),"ISO",List.of(),null));
   Preview lockedTarget=previews.create(owner,("ID,Notes\n"+applicationId+",Lock proof\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),updateOptions);
   ExecutorService targetPool=Executors.newSingleThreadExecutor();CountDownLatch targetLocked=new CountDownLatch(1),targetRelease=new CountDownLatch(1);
   Future<?> targetHeld=targetPool.submit(()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{
     jdbc.queryForObject("select id from applications where id=? for update",UUID.class,applicationId);targetLocked.countDown();
     try {assertThat(targetRelease.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}
   }));
   try {
     assertThat(targetLocked.await(5,TimeUnit.SECONDS)).isTrue();long before=System.nanoTime();
     assertThatThrownBy(()->commits.commit(owner,lockedTarget.id(),new CommitRequest(lockedTarget.reviewDigest(),new Acknowledgement(1))))
         .isInstanceOfAny(org.springframework.dao.DataAccessException.class,jakarta.persistence.PersistenceException.class);
     assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)).isLessThan(6000);
   } finally {targetRelease.countDown();targetHeld.get(15,TimeUnit.SECONDS);targetPool.shutdownNow();}
   assertThat(statuses.status(owner,lockedTarget.id()).status()).isEqualTo("PREVIEW");
   assertThat(commits.commit(owner,lockedTarget.id(),new CommitRequest(lockedTarget.reviewDigest(),new Acknowledgement(1))).counts().updated()).isEqualTo(1);

   UUID foreignOwner=importAccount(jdbc);
   Preview foreign=previews.create(foreignOwner,("ID,Notes\n"+applicationId+",Unavailable\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),updateOptions);
   assertThat(foreign.rows().get(0).issues()).extracting(Issue::code).contains("IMPORT_TARGET_UNAVAILABLE");
   assertThat(foreign.rows().get(0).before()).isNull();
   assertThatThrownBy(()->statuses.status(foreignOwner,preview.id())).isInstanceOfSatisfying(com.campus.tracker.common.ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_NOT_FOUND"));
   Preview deletedTarget=previews.create(owner,("ID,Notes\n"+applicationId+",Deleted race\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),updateOptions);
   context.getBean(com.campus.tracker.application.ApplicationService.class).delete(owner,applicationId,"1");
   assertThatThrownBy(()->commits.commit(owner,deletedTarget.id(),new CommitRequest(deletedTarget.reviewDigest(),new Acknowledgement(1))))
       .isInstanceOfSatisfying(com.campus.tracker.common.ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_TARGET_CHANGED"));

   Preview expired=previews.create(owner,file,options);
   jdbc.update("update application_imports set expires_at=? where id=?",Timestamp.from(Instant.now().minusSeconds(1)),expired.id());
   ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
   Future<?> held=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{
     jdbc.queryForObject("select id from application_imports where id=? for update",UUID.class,expired.id());locked.countDown();
     try {assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}
   }));
   try {
     assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
     assertThat(statuses.status(owner,expired.id()).status()).isEqualTo("UNRESOLVED");
     cleanup.cleanup();
     assertThat(jdbc.queryForObject("select payload_bytes from application_imports where id=?",Long.class,expired.id())).isPositive();
   } finally {release.countDown();held.get(15,TimeUnit.SECONDS);pool.shutdownNow();}
   cleanup.cleanup();
   assertThatThrownBy(()->statuses.status(owner,expired.id())).isInstanceOf(com.campus.tracker.common.ApiException.class);
   assertThat(statuses.status(owner,preview.id()).receipt()).isEqualTo(original);

   UUID quotaOwner=importAccount(jdbc);ExecutorService admissionPool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
   try {
     List<Future<Boolean>> attempts=new ArrayList<>();
     for(int i=0;i<8;i++) attempts.add(admissionPool.submit(()->{start.await();try {previews.create(quotaOwner,file,options);return true;}catch(org.springframework.dao.DataAccessException|com.campus.tracker.common.ApiException busy){return false;}}));
     start.countDown();int admitted=0;for(var result:attempts) if(result.get(20,TimeUnit.SECONDS)) admitted++;
     assertThat(admitted).isBetween(1,5);
     assertThat(jdbc.queryForObject("select count(*) from application_imports where owner_id=? and state='PREVIEW'",Long.class,quotaOwner)).isEqualTo(admitted);
     while(admitted<5) {previews.create(quotaOwner,file,options);admitted++;}
     assertThatThrownBy(()->previews.create(quotaOwner,file,options)).isInstanceOfSatisfying(com.campus.tracker.common.ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_STORAGE_LIMIT"));
   } finally {admissionPool.shutdownNow();}

   // Receipt-count and byte accounting must remain charged while PREVIEW converts to COMPLETED.
   for(int receiptCountLimit:List.of(2,100)) {
   var limits=new ImportStorageProperties(5,100,41943040,838860800,receiptCountLimit,100000,262144,2147483648L,300,10000);
   var narrowAdmission=new ImportAdmissionService(jdbc,context.getBean(Clock.class),limits);
   var narrowPreviews=new ImportPreviewService(context.getBean(CsvImportParser.class),context.getBean(ImportSemantics.class),
       context.getBean(com.campus.tracker.application.ApplicationService.class),context.getBean(ImportCanonicalizer.class),context.getBean(ImportPreviewRepository.class),narrowAdmission,context.getBean(Clock.class),manager);
   for(int iteration=0;iteration<5;iteration++) {
     UUID boundedOwner=importAccount(jdbc);
     Preview first=narrowPreviews.create(boundedOwner,file,options);narrowPreviews.create(boundedOwner,file,options);
     List<Object> race=importRace(()->commits.commit(boundedOwner,first.id(),new CommitRequest(first.reviewDigest(),new Acknowledgement(0))),()->narrowPreviews.create(boundedOwner,file,options));
     assertThat(race.get(0)).isInstanceOf(Receipt.class);
     assertThat(race.get(1)).isInstanceOfSatisfying(com.campus.tracker.common.ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_STORAGE_LIMIT"));
     assertThat(jdbc.queryForObject("select count(*) from application_imports where owner_id=? and state in ('PREVIEW','COMPLETED')",Long.class,boundedOwner)).isEqualTo(2);
   }
   }
 }
 private void verifyImportMaximumBatch(org.springframework.context.ConfigurableApplicationContext context) throws Exception {
   var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);UUID owner=importAccount(jdbc);
   var previews=context.getBean(ImportPreviewService.class);var commits=context.getBean(ImportCommitService.class);var json=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
   StringBuilder csv=new StringBuilder("Company,Role\n");for(int i=0;i<500;i++)csv.append("PG Company ").append(i).append(",Intern\n");
   String options=json.writeValueAsString(new Options(1,Map.of("0","company","1","role"),"ISO",List.of(),null));
   Preview add=previews.create(owner,csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),options);
   long started=System.nanoTime();Receipt created=commits.commit(owner,add.id(),new CommitRequest(add.reviewDigest(),new Acknowledgement(0)));
   System.out.println("Restricted PostgreSQL 500 Add commit ms: "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
   assertThat(created.rows()).hasSize(500);
   csv=new StringBuilder("ID,Notes\n");for(var row:created.rows())csv.append(row.applicationId()).append(",Updated 500-row PG import\n");
   options=json.writeValueAsString(new Options(1,Map.of("0","applicationId","1","notes"),"ISO",List.of(),null));
   Preview update=previews.create(owner,csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),options);
   assertThat(update.counts().updated()).isEqualTo(500);started=System.nanoTime();
   Receipt changed=commits.commit(owner,update.id(),new CommitRequest(update.reviewDigest(),new Acknowledgement(500)));
   System.out.println("Restricted PostgreSQL 500 Update commit ms: "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
   assertThat(changed.rows()).allSatisfy(row->assertThat(row.resultVersion()).isEqualTo(1));
   Preview same=previews.create(owner,csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),options);
   assertThat(same.counts().unchanged()).isEqualTo(500);
   Receipt unchanged=commits.commit(owner,same.id(),new CommitRequest(same.reviewDigest(),new Acknowledgement(0)));
   assertThat(unchanged.rows()).allSatisfy(row->assertThat(row.resultVersion()).isEqualTo(1));
   assertThat(jdbc.queryForObject("select count(*) from applications where owner_id=?",Long.class,owner)).isEqualTo(500);
 }
 private UUID importAccount(org.springframework.jdbc.core.JdbcTemplate jdbc) {
   UUID owner=UUID.randomUUID();jdbc.update("insert into campus_users (id,email,display_name,password_hash,created_at) values (?,?,?,?,?)",owner,owner+"@example.test","PG import","unused",Timestamp.from(Instant.now()));return owner;
 }
 private List<Object> importRace(Callable<?> first,Callable<?> second) throws Exception {
   ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
   try {List<Future<Object>> results=new ArrayList<>();for(Callable<?> action:List.of(first,second))results.add(pool.submit(()->{start.await();try{return action.call();}catch(Exception e){return e;}}));start.countDown();return List.of(results.get(0).get(30,TimeUnit.SECONDS),results.get(1).get(30,TimeUnit.SECONDS));}
   finally {pool.shutdownNow();}
 }
 private void verifyImportHttp(java.net.http.HttpClient client,String base,String token,
       com.fasterxml.jackson.databind.ObjectMapper json,com.fasterxml.jackson.databind.JsonNode existing) throws Exception {
   String csv="Company,Role,Notes\nPostgres CSV,Intern,=literal\n";
   var options=json.createObjectNode().put("schemaVersion",1).put("dateFormat","ISO");
   options.putObject("mapping").put("0","company").put("1","role").put("2","notes");
   options.putArray("rows");
   importPreview(client,base,null,csv,json.writeValueAsString(options),403);
   var original=json.readTree(importPreview(client,base,token,csv,json.writeValueAsString(options),201));
   var preview=original;
   // Repeated corrections must release the predecessor reservation under the runtime role.
   for(int i=0;i<6;i++) {
     options.put("supersedesPreviewId",preview.get("id").asText());
     preview=json.readTree(importPreview(client,base,token,csv,json.writeValueAsString(options),201));
   }
   var oldStatus=json.readTree(http(client,base,"GET","/api/application-imports/"+original.get("id").asText(),null,null,200).body());
   assertThat(oldStatus.get("status").asText()).isEqualTo("SUPERSEDED");
   var confirmation=json.createObjectNode().put("reviewDigest",preview.get("reviewDigest").asText());
   confirmation.putObject("acknowledgement").put("existingApplicationsToUpdate",0);
   String commitPath="/api/application-imports/"+preview.get("id").asText()+"/commit";
   http(client,base,"POST",commitPath,json.writeValueAsString(confirmation),null,403);
   var receipt=json.readTree(http(client,base,"POST",commitPath,json.writeValueAsString(confirmation),token,200).body());
   assertThat(receipt.get("counts").get("added").asInt()).isEqualTo(1);
   assertThat(json.readTree(http(client,base,"POST",commitPath,json.writeValueAsString(confirmation),token,200).body())).isEqualTo(receipt);
   var status=json.readTree(http(client,base,"GET","/api/application-imports/"+preview.get("id").asText(),null,null,200).body());
   assertThat(status.get("receipt")).isEqualTo(receipt);
   assertThat(json.readTree(http(client,base,"GET","/api/applications/"+receipt.get("rows").get(0).get("applicationId").asText(),null,null,200).body()).get("notes").asText()).isEqualTo("=literal");

   var updateOptions=json.createObjectNode().put("schemaVersion",1).put("dateFormat","ISO");
   updateOptions.putObject("mapping").put("0","applicationId").put("1","notes");updateOptions.putArray("rows");
   var update=json.readTree(importPreview(client,base,token,"Application ID,Notes\n"+existing.get("id").asText()+",Updated through import\n",json.writeValueAsString(updateOptions),201));
   assertThat(update.get("rows").get(0).get("after").get("company").asText()).isEqualTo(existing.get("company").asText());
   var updateRequest=json.createObjectNode().put("reviewDigest",update.get("reviewDigest").asText());
   updateRequest.putObject("acknowledgement").put("existingApplicationsToUpdate",1);
   var updatedReceipt=json.readTree(http(client,base,"POST","/api/application-imports/"+update.get("id").asText()+"/commit",json.writeValueAsString(updateRequest),token,200).body());
   assertThat(updatedReceipt.get("counts").get("updated").asInt()).isEqualTo(1);
   assertThat(json.readTree(http(client,base,"GET","/api/applications/"+existing.get("id").asText(),null,null,200).body()).get("notes").asText()).isEqualTo("Updated through import");
 }
 private String importPreview(java.net.http.HttpClient client,String base,String token,String csv,String options,int status) throws Exception {
   String boundary="campus-postgres-test";
   String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.csv\"\r\nContent-Type: text/csv\r\n\r\n"+csv
       +"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"options\"\r\nContent-Type: application/json\r\n\r\n"+options+"\r\n--"+boundary+"--\r\n";
   var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/application-imports/preview"))
       .timeout(java.time.Duration.ofSeconds(20)).header("Content-Type","multipart/form-data; boundary="+boundary)
       .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body,java.nio.charset.StandardCharsets.UTF_8));
   if(token!=null)request.header("X-CSRF-TOKEN",token);
   var response=client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
   assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
   assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
   return response.body();
 }
 private void verifyConcurrentAuth(org.springframework.context.ConfigurableApplicationContext context) throws Exception {
  var limiter=context.getBean(com.campus.tracker.auth.verification.AuthRateLimiter.class);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(12);
  var start=new java.util.concurrent.CountDownLatch(1);
  try {
   var attempts=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
   for(int i=0;i<12;i++) attempts.add(pool.submit(()->{
    start.await();
    try {limiter.requireRequestAllowed("192.0.2.150");return true;}
    catch(com.campus.tracker.common.ApiException denied) {assertThat(denied.getStatus().value()).isEqualTo(429);return false;}
   }));
   start.countDown();int accepted=0;
   for(var attempt:attempts) if(attempt.get(10,java.util.concurrent.TimeUnit.SECONDS)) accepted++;
   assertThat(accepted).isEqualTo(5);

   var realPasswords=context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
   var barrier=new java.util.concurrent.CyclicBarrier(2);
   var passwords=new org.springframework.security.crypto.password.PasswordEncoder() {
    public String encode(CharSequence raw) {
     try {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
     return realPasswords.encode(raw);
    }
    public boolean matches(CharSequence raw,String hash) {return realPasswords.matches(raw,hash);}
   };
   var users=context.getBean(com.campus.tracker.auth.UserRepository.class);
   var accounts=new com.campus.tracker.auth.AccountService(users,passwords,
       context.getBean(com.campus.tracker.application.SampleDataService.class),
       context.getBean(com.campus.tracker.auth.verification.EmailVerificationService.class),
       context.getBean(org.springframework.transaction.PlatformTransactionManager.class),true);
   String email=java.util.UUID.randomUUID()+"@example.test";
   var input=new com.campus.tracker.auth.AuthDtos.Registration("Concurrent",email,"Concurrent-test-123!",true);
   var first=pool.submit(()->accounts.register(input));var second=pool.submit(()->accounts.register(input));
   assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("VERIFICATION_REQUEST_ACCEPTED");
   assertThat(second.get(20,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("VERIFICATION_REQUEST_ACCEPTED");
   var user=users.findByEmail(email).orElseThrow();
   var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_users WHERE email=?",Long.class,email)).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM applications WHERE owner_id=?",Long.class,user.getId())).isEqualTo(10);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE verification_token_id IN "
       +"(SELECT id FROM email_verification_tokens WHERE user_id=?)",Long.class,user.getId())).isEqualTo(1);
  } finally {pool.shutdownNow();}
 }
 static java.net.http.HttpResponse<String> http(java.net.http.HttpClient client,String base,String method,
      String path,String body,String token,int status) throws Exception {
  return httpVersion(client,base,method,path,body,token,null,status);
 }
 static java.net.http.HttpResponse<String> httpVersion(java.net.http.HttpClient client,String base,String method,
      String path,String body,String token,String version,int status) throws Exception {
  var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+path)).timeout(java.time.Duration.ofSeconds(20))
      .method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(body));
  if(body!=null) request.header("Content-Type",path.equals("/api/auth/login")?"application/x-www-form-urlencoded":"application/json");
  if(token!=null) request.header("X-CSRF-TOKEN",token);
  if(version!=null) request.header("If-Match",version);
  var response=client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
  assertThat(response.statusCode()).as(method+" "+path+" "+response.body()).isEqualTo(status);
  return response;
 }
 /** Local mode always initializes its own cluster; it cannot target an existing database. */
 static final class TestDatabase implements AutoCloseable {
  private PostgreSQLContainer<?> container;
  private String containerJdbcUrl;
  private Path binaryDirectory, dataDirectory, log;
  private int port;
  private final String username;
  private final String password="isolated-admin-password";
  TestDatabase(boolean legacy) throws Exception {
   username=legacy?"campus":"campus_admin";
   String binaries=System.getProperty("postgres.test.bin");
   if(binaries==null) {
    container=new PostgreSQLContainer<>("postgres:17").withDatabaseName("campus").withUsername(username).withPassword(password)
     .withEnv("MIGRATION_DATABASE_PASSWORD","migration-'quote-password").withEnv("RUNTIME_DATABASE_PASSWORD","runtime-'quote-password")
     .withEnv("RETENTION_DATABASE_PASSWORD","retention-'quote-password");
    container.start();
    containerJdbcUrl=container.getJdbcUrl();
    container.copyFileToContainer(MountableFile.forHostPath(Path.of("../deploy/postgres").toAbsolutePath()),"/scripts");
    container.copyFileToContainer(MountableFile.forHostPath(Path.of("../deploy/monitoring").toAbsolutePath()),"/monitoring");
   } else {
    binaryDirectory=Path.of(binaries).toAbsolutePath();
    Path root=java.nio.file.Files.createTempDirectory(Path.of("target").toAbsolutePath(),"postgres-privileges-");
    dataDirectory=root.resolve("data"); log=root.resolve("server.log");
    Path pw=root.resolve("password.txt");java.nio.file.Files.writeString(pw,password);
    try(var socket=new java.net.ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())) {port=socket.getLocalPort();}
    check(process("initdb","-D",dataDirectory.toString(),"-U",username,"--auth=scram-sha-256","--pwfile="+pw,"--encoding=UTF8","--locale=C"));
    check(process("pg_ctl","-D",dataDirectory.toString(),"-l",log.toString(),"-o","-h 127.0.0.1 -p "+port,"-w","start"));
    try(var c=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+port+"/postgres",username,password);var s=c.createStatement()) {s.execute("CREATE DATABASE campus");}
   }
  }
  String getJdbcUrl(){return container!=null?containerJdbcUrl:"jdbc:postgresql://127.0.0.1:"+port+"/campus";}
  String getUsername(){return username;}
  String getPassword(){return password;}
  String restoredJdbcUrl(){return getJdbcUrl().replace("/campus","/campus_restored");}
  void backup() throws Exception {
   check(databaseCommand("pg_dump","-Fc","-U",username,"-d","campus","-f",backupPath()));
  }
  void restore() throws Exception {
   try(var c=DriverManager.getConnection(getJdbcUrl(),username,password);var s=c.createStatement()) {
    s.execute("CREATE DATABASE campus_restored");
    s.execute("REVOKE CONNECT ON DATABASE campus_restored FROM PUBLIC");
    s.execute("GRANT CONNECT ON DATABASE campus_restored TO campus_runtime,campus_migrator,campus_retention");
   }
   check(databaseCommand("pg_restore","--exit-on-error","-U",username,"-d","campus_restored",backupPath()));
  }
  void restart() throws Exception {
   String previousUrl=getJdbcUrl();
   if(container!=null) {
    container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();
    // Raw Docker restart can remap the ephemeral host port; Testcontainers retains its startup inspection.
    var bindings=container.getCurrentContainerInfo().getNetworkSettings().getPorts().getBindings()
        .get(com.github.dockerjava.api.model.ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT));
    assertThat(bindings).as("Restarted PostgreSQL port mapping").isNotEmpty();
    containerJdbcUrl=container.getJdbcUrl().replace(":"+container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)+"/",
        ":"+bindings[0].getHostPortSpec()+"/");
   } else check(process("pg_ctl","-D",dataDirectory.toString(),"-m","fast","-w","restart"));
   System.out.println("PostgreSQL restart endpoint: "+previousUrl+" -> "+getJdbcUrl());
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
   while(true) {
    try(var connection=DriverManager.getConnection(getJdbcUrl(),username,password)) {return;}
    catch(SQLException unavailable) {if(System.nanoTime()>=deadline) throw unavailable;Thread.sleep(100);}
   }
  }
  private String backupPath(){return container!=null?"/tmp/pre-reset.dump":dataDirectory.getParent().resolve("pre-reset.dump").toString();}
  private Result databaseCommand(String executable,String... arguments) throws Exception {
   var args=new java.util.ArrayList<String>(java.util.List.of(arguments));
   if(container!=null) {
    args.add(0,executable);var result=container.execInContainer(args.toArray(String[]::new));
    return new Result(result.getExitCode(),result.getStderr()+result.getStdout());
   }
   args.addAll(java.util.List.of("-h","127.0.0.1","-p",Integer.toString(port)));
   return process(executable,args.toArray(String[]::new));
  }
  Result psql(String... arguments) throws Exception {
   String[] args=arguments.clone();
   args[args.length-1]=(container!=null?"/scripts/":Path.of("../deploy/postgres").toAbsolutePath()+"/")+args[args.length-1];
   if(container!=null) {var r=container.execInContainer(args);return new Result(r.getExitCode(),r.getStderr()+r.getStdout());}
   java.util.List<String> command=new java.util.ArrayList<>(java.util.List.of(args));
   command.addAll(java.util.List.of("-h","127.0.0.1","-p",Integer.toString(port)));
   return process(command.remove(0),command.toArray(String[]::new));
  }
  private Result process(String executable,String... args) throws Exception {
   var command=new java.util.ArrayList<String>();command.add(binaryDirectory.resolve(executable+".exe").toString());command.addAll(java.util.List.of(args));
   var builder=new ProcessBuilder(command).redirectErrorStream(true);
   builder.environment().put("PGPASSWORD",password);
   builder.environment().put("MIGRATION_DATABASE_PASSWORD","migration-'quote-password");
   builder.environment().put("RUNTIME_DATABASE_PASSWORD","runtime-'quote-password");
   builder.environment().put("RETENTION_DATABASE_PASSWORD","retention-'quote-password");
   // Windows descendants can keep inherited pipe handles open after pg_ctl exits.
   Path output=java.nio.file.Files.createTempFile(dataDirectory.getParent(),"process-",".log");
   builder.redirectOutput(output.toFile());
   var process=builder.start();
   if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)) {
    process.destroyForcibly();
    throw new IllegalStateException("PostgreSQL test command timed out: "+executable);
   }
   return new Result(process.exitValue(),java.nio.file.Files.readString(output));
  }
  private void check(Result r){assertThat(r.getExitCode()).withFailMessage(r.getStderr()).isZero();}
  public void close() throws Exception {
   if(container!=null) container.close();
   else if(dataDirectory!=null) check(process("pg_ctl","-D",dataDirectory.toString(),"-m","fast","-w","stop"));
  }
 }
 record Result(int getExitCode,String getStderr) {}
}
