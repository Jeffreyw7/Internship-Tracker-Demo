package com.campus.tracker;

import com.campus.tracker.auth.reset.PasswordResetRequestWorker;
import com.campus.tracker.mail.EmailMessage;
import com.campus.tracker.mail.OutboxCrypto;
import com.campus.tracker.migration.MigrationMain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import static com.campus.tracker.PostgresPrivilegesIT.http;
import static org.assertj.core.api.Assertions.*;

/** Real logical backup/restore and clean database restart; never connects to an operator database. */
class PasswordResetRestoreIT {
 private final ObjectMapper json=new ObjectMapper();
 private static final String EMAIL="restore-drill@example.test";
 private static final String OLD_PASSWORD="Original campus passphrase 4982";
 private static final String RESET_PASSWORD="Changed campus passphrase 7394";
 private static final String RECOVERED_PASSWORD="Reconciled campus passphrase 9183";

 @Test void restoreReconciliationPreventsSpentLinksAndOldPasswordsReturning() throws Exception {
  try(var db=new PostgresPrivilegesIT.TestDatabase(false)) {
   var provision=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","provision.sql");
   assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator",
       "MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   var source=admin(db,db.getJdbcUrl());
   Link spent;
   String originalHash;
   UUID account=UUID.randomUUID();
   var oldSession=client();
   try(var app=PostgresPrivilegesIT.startContext(db.getJdbcUrl())) {
    originalHash=app.getBean(PasswordEncoder.class).encode(OLD_PASSWORD);
    source.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at,email_verified_at,reset_request_cutoff) "
        +"VALUES(?,?,?, ?,clock_timestamp(),clock_timestamp(),50)",account,EMAIL,"Restore drill",originalHash);
    source.execute("SELECT setval('auth_security_event_order',51,false)");
    source.update("UPDATE password_reset_control SET enabled=true WHERE id=1");
    String base=base(app);
    login(oldSession,base,OLD_PASSWORD,200);
    spent=requestLink(app,base);
    seedQueuedVerificationAndIntake(source,account);
    // A backup with an active link and old credential is intentionally taken before completion.
    db.backup();
    confirm(client(),base,spent.token(),RESET_PASSWORD,200);
    http(oldSession,base,"GET","/api/auth/me",null,null,401);
    login(client(),base,OLD_PASSWORD,401);
    login(client(),base,RESET_PASSWORD,200);
   }

   db.restart();
   source=admin(db,db.getJdbcUrl());
   // Password, epoch, token consumption, audit and notice survive a real clean PostgreSQL restart.
   assertThat(source.queryForObject("SELECT credential_version FROM campus_users WHERE id=?",Long.class,account)).isEqualTo(1);
   assertThat(source.queryForObject("SELECT consumed_at IS NOT NULL FROM password_reset_tokens WHERE id=?",Boolean.class,spent.id())).isTrue();
   assertThat(source.queryForObject("SELECT COUNT(*) FROM auth_security_events WHERE completion_id=?",Long.class,spent.id())).isEqualTo(1);
   assertThat(source.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE password_reset_token_id=? AND message_type='PASSWORD_CHANGED'",Long.class,spent.id())).isEqualTo(1);
   try(var restarted=PostgresPrivilegesIT.startContext(db.getJdbcUrl())) {
    login(client(),base(restarted),OLD_PASSWORD,401);
    login(client(),base(restarted),RESET_PASSWORD,200);
    confirm(client(),base(restarted),spent.token(),RECOVERED_PASSWORD,400);
   }

   db.restore();
   var restored=admin(db,db.restoredJdbcUrl());
   // Inspect rollback offline: do not start an application against this unsafe restored state.
   assertThat(restored.queryForObject("SELECT password_hash FROM campus_users WHERE id=?",String.class,account)).isEqualTo(originalHash);
   assertThat(restored.queryForObject("SELECT credential_version FROM campus_users WHERE id=?",Long.class,account)).isZero();
   assertThat(restored.queryForObject("SELECT consumed_at IS NULL AND revoked_at IS NULL AND expires_at>clock_timestamp() FROM password_reset_tokens WHERE id=?",Boolean.class,spent.id())).isTrue();
   assertThat(restored.queryForObject("SELECT COUNT(*) FROM auth_security_events WHERE completion_id=?",Long.class,spent.id())).isZero();
   long auditBefore=restored.queryForObject("SELECT COUNT(*) FROM auth_security_events",Long.class);
   long orderFloor=restored.queryForObject("SELECT MAX(request_order) FROM password_reset_requests",Long.class);
   restored.execute("SELECT setval('auth_security_event_order',1,false)");
   // Refuse missing/wrong confirmation and a mistaken target before any data changes.
   assertThat(reconcile(db,null,"campus_restored").getExitCode()).isNotZero();
   assertThat(reconcile(db,"ISOLATED_RESTORE_OFFLINE_WRONG","campus_restored").getExitCode()).isNotZero();
   assertThat(reconcile(db,"ISOLATED_RESTORE_OFFLINE",null).getExitCode()).isNotZero();
   assertThat(reconcile(db,"ISOLATED_RESTORE_OFFLINE","campus").getExitCode()).isNotZero();
   assertThat(restored.queryForObject("SELECT password_hash FROM campus_users WHERE id=?",String.class,account)).isEqualTo(originalHash);
   var reconciled=reconcile(db,"ISOLATED_RESTORE_OFFLINE","campus_restored");
   assertThat(reconciled.getExitCode()).withFailMessage(reconciled.getStderr()).isZero();
   assertThat(restored.queryForObject("SELECT NOT enabled AND operator_intake_paused AND health_intake_paused FROM password_reset_control WHERE id=1",Boolean.class)).isTrue();
   assertThat(restored.queryForObject("SELECT COUNT(*) FROM campus_users WHERE password_hash<>'!restore-recovery-required'",Long.class)).isZero();
   assertThat(restored.queryForObject("SELECT reset_request_cutoff FROM campus_users WHERE id=?",Long.class,account)).isGreaterThan(orderFloor);
   assertThat(restored.queryForObject("SELECT credential_version FROM campus_users WHERE id=?",Long.class,account)).isEqualTo(1);
   assertThat(restored.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE consumed_at IS NULL AND revoked_at IS NULL",Long.class)).isZero();
   assertThat(restored.queryForObject("SELECT COUNT(*) FROM email_verification_tokens WHERE consumed_at IS NULL AND revoked_at IS NULL",Long.class)).isZero();
   assertThat(restored.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE status<>'CANCELLED' OR payload IS NOT NULL OR key_id IS NOT NULL OR nonce IS NOT NULL OR lease_owner IS NOT NULL OR lease_expires_at IS NOT NULL",Long.class)).isZero();
   assertThat(restored.queryForObject("SELECT COUNT(*) FROM password_reset_requests WHERE status<>'CANCELLED' OR payload IS NOT NULL OR key_id IS NOT NULL OR nonce IS NOT NULL OR lease_owner IS NOT NULL OR lease_expires_at IS NOT NULL OR completed_at IS NULL",Long.class)).isZero();
   assertThat(restored.queryForObject("SELECT COUNT(*) FROM auth_security_events",Long.class)).isEqualTo(auditBefore);

   // A new app process has no old in-memory sessions; external session stores must be cleared separately.
   try(var app=PostgresPrivilegesIT.startContext(db.restoredJdbcUrl())) {
    String base=base(app);
    http(oldSession,base,"GET","/api/auth/me",null,null,401);
    login(client(),base,OLD_PASSWORD,401);
    login(client(),base,RESET_PASSWORD,401);
    var blocked=client();
    http(blocked,base,"POST","/api/auth/password-reset/request",json.writeValueAsString(Map.of("email",EMAIL)),csrf(blocked,base),503);
    confirm(client(),base,spent.token(),RECOVERED_PASSWORD,503);
    // Controlled test-only recovery enable; production remains gated on offline checks and session clearing.
    restored.update("UPDATE password_reset_control SET enabled=true,operator_intake_paused=false,health_intake_paused=false WHERE id=1");
    confirm(client(),base,spent.token(),RECOVERED_PASSWORD,400);
    // Simulate passage of only the synthetic account's minute cooldown, retaining every durable counter.
    restored.update("UPDATE auth_rate_limits SET window_start=window_start-INTERVAL '1 minute' WHERE scope='reset-email-minute'");
    Link fresh=requestLink(app,base);
    assertThat(fresh.id()).isNotEqualTo(spent.id());
    confirm(client(),base,fresh.token(),RECOVERED_PASSWORD,200);
    login(client(),base,RECOVERED_PASSWORD,200);
    login(client(),base,OLD_PASSWORD,401);
    login(client(),base,RESET_PASSWORD,401);
    confirm(client(),base,spent.token(),OLD_PASSWORD,400);
    confirm(client(),base,fresh.token(),OLD_PASSWORD,400);
    assertThat(restored.queryForObject("SELECT COUNT(*) FROM auth_security_events WHERE completion_id=?",Long.class,fresh.id())).isEqualTo(1);
   }
  }
 }

 private void seedQueuedVerificationAndIntake(JdbcTemplate jdbc,UUID account) {
  UUID verification=UUID.randomUUID();
  jdbc.update("INSERT INTO email_verification_tokens(id,user_id,email,token_hash,created_at,expires_at) VALUES(?,?,?,?,clock_timestamp(),clock_timestamp()+INTERVAL '30 minutes')",verification,account,EMAIL,"b".repeat(64));
  jdbc.update("INSERT INTO email_outbox(id,verification_token_id,payload,key_id,nonce,status,next_attempt_at,created_at) VALUES(?,?,?,? ,?,'PENDING',clock_timestamp(),clock_timestamp())",UUID.randomUUID(),verification,"isolated-encrypted-placeholder","test","nonce");
  jdbc.update("INSERT INTO password_reset_requests(id,request_order,payload,key_id,nonce,status,next_attempt_at,created_at,expires_at) VALUES(?,nextval('auth_security_event_order'),?,?,?,'PENDING',clock_timestamp(),clock_timestamp(),clock_timestamp()+INTERVAL '10 minutes')",UUID.randomUUID(),"isolated-encrypted-placeholder","test","nonce");
  jdbc.update("INSERT INTO auth_security_events(id,completion_id,event_type,occurred_at,account_subject,correlation_id) VALUES(?,?,'PASSWORD_RESET_COMPLETED',clock_timestamp(),'test:preserved',?)",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
 }
 private PostgresPrivilegesIT.Result reconcile(PostgresPrivilegesIT.TestDatabase db,String confirmation,String expectedDatabase) throws Exception {
  var args=new java.util.ArrayList<String>(java.util.List.of("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus_restored"));
  if(expectedDatabase!=null) args.addAll(java.util.List.of("-v","restore_database="+expectedDatabase));
  if(confirmation!=null) args.addAll(java.util.List.of("-v","restore_confirmation="+confirmation));
  args.addAll(java.util.List.of("-f","reconcile-restored-auth.sql"));
  return db.psql(args.toArray(String[]::new));
 }
 private Link requestLink(ConfigurableApplicationContext app,String base) throws Exception {
  var browser=client();
  http(browser,base,"POST","/api/auth/password-reset/request",json.writeValueAsString(Map.of("email",EMAIL)),csrf(browser,base),202);
  app.getBean(PasswordResetRequestWorker.class).processPending();
  var job=app.getBean(JdbcTemplate.class).queryForMap("SELECT o.id,o.password_reset_token_id,o.payload,o.nonce,o.key_id FROM email_outbox o JOIN password_reset_tokens t ON t.id=o.password_reset_token_id WHERE t.email=? AND o.message_type='PASSWORD_RESET_LINK' AND o.status='PENDING'",EMAIL);
  var message=app.getBean(OutboxCrypto.class).decrypt((UUID)job.get("id"),EmailMessage.Type.PASSWORD_RESET_LINK,(String)job.get("payload"),(String)job.get("nonce"),(String)job.get("key_id"));
  assertThat(message.recipient()).isEqualTo(EMAIL);
  return new Link((UUID)job.get("password_reset_token_id"),message.token());
 }
 private void confirm(HttpClient browser,String base,String token,String password,int status) throws Exception {
  var result=http(browser,base,"POST","/api/auth/password-reset/confirm",json.writeValueAsString(Map.of("token",token,"newPassword",password)),csrf(browser,base),status);
  if(status==400) assertThat(json.readTree(result.body()).get("code").asText()).isEqualTo("PASSWORD_RESET_INVALID");
 }
 private void login(HttpClient browser,String base,String password,int status) throws Exception {
  http(browser,base,"POST","/api/auth/login","email="+URLEncoder.encode(EMAIL,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8),csrf(browser,base),status);
 }
 private String csrf(HttpClient browser,String base) throws Exception {return json.readTree(http(browser,base,"GET","/api/auth/csrf",null,null,200).body()).get("token").asText();}
 private static HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
 private static String base(ConfigurableApplicationContext app){return "http://127.0.0.1:"+((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)app).getWebServer().getPort();}
 private static JdbcTemplate admin(PostgresPrivilegesIT.TestDatabase db,String url){return new JdbcTemplate(new DriverManagerDataSource(url,db.getUsername(),db.getPassword()));}
 private record Link(UUID id,String token) {}
}
