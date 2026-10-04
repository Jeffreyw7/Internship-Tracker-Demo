package com.campus.tracker;

import com.campus.tracker.migration.MigrationMain;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import java.nio.file.Path;
import java.sql.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PostgresPrivilegesIT {
 @Test void freshDatabaseHasOnlyRequiredRuntimePrivileges() throws Exception { verify(false); }
 @Test void legacyUpgradePreservesRecordsAndMigrationHistory() throws Exception { verify(true); }
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
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator","MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   // Repeat deployment: migrations and grants are idempotent.
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator","MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   try(var c=DriverManager.getConnection(db.getJdbcUrl(),"campus_runtime","runtime-'quote-password");var s=c.createStatement()) {
    if(legacy) {try(var r=s.executeQuery("SELECT display_name FROM campus_users")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("Legacy");}}
    s.execute("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES ('00000000-0000-0000-0000-000000000002','new@example.test','New','hash',CURRENT_TIMESTAMP)");
    s.execute("UPDATE campus_users SET theme_preference='dark',email_verified_at=CURRENT_TIMESTAMP");
    s.execute("INSERT INTO applications(id,owner_id,company,role,location,status,notes,created_at,updated_at) VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','Company','Role','Remote','SAVED','',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    s.execute("UPDATE applications SET deleted=TRUE,version=version+1");
    s.execute("UPDATE applications SET deleted=FALSE,version=version+1");
    s.execute("INSERT INTO interview_stages VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003',0,'Interview',NULL,'PLANNED')");
    s.execute("UPDATE interview_stages SET name='Changed'");
    assertThat(s.executeUpdate("DELETE FROM interview_stages")).isEqualTo(1);
    for(String denied : new String[]{"CREATE TABLE public.forbidden_probe(id int)","CREATE TEMP TABLE forbidden_temp(id int)","CREATE SCHEMA forbidden_schema","CREATE ROLE forbidden_role","CREATE DATABASE forbidden_db","DROP TABLE applications","ALTER TABLE applications ADD COLUMN forbidden text","TRUNCATE applications","DELETE FROM applications","DELETE FROM campus_users","UPDATE campus_users SET display_name='forbidden'","UPDATE campus_users SET email_verification_exempt=TRUE","UPDATE campus_users SET password_hash='forbidden'","SELECT * FROM flyway_schema_history","SET ROLE campus_migrator","SET ROLE "+db.getUsername()}) {
     assertThatThrownBy(()->s.execute(denied)).as(denied).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
    }
   }
   verifyHttp(db);
   var verification=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","verify-runtime.sql");
   assertThat(verification.getExitCode()).withFailMessage(verification.getStderr()).isZero();
  }
 }
 private void verifyHttp(TestDatabase db) throws Exception {
  try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(CampusApplication.class)
      .profiles("prod").run(
          "--server.port=0","--server.address=127.0.0.1",
          "--spring.datasource.url="+db.getJdbcUrl(),
          "--spring.datasource.username=campus_runtime",
          "--spring.datasource.password=runtime-'quote-password",
          "--campus.registration-enabled=true","--server.servlet.session.cookie.secure=false",
          "--spring.mail.host=127.0.0.1", "--spring.mail.port=1", "--spring.mail.username=", "--spring.mail.password=",
          "--campus.mail.public-origin=http://localhost", "--campus.mail.sender=capture@example.test",
          "--campus.mail.key-id=isolated-test", "--campus.mail.encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
          "--campus.mail.production=false", "--campus.mail.worker-enabled=false",
          "--campus.verification.rate-limit-secret=isolated-postgres-verification-secret",
          "--debug=false","--logging.level.root=WARN")) {
   var server=(org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context;
   verifyConcurrentAuth(context);
   String base="http://127.0.0.1:"+server.getWebServer().getPort();
   var cookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
   var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
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
       (java.util.UUID)outbox.get("id"),(String)outbox.get("payload"),(String)outbox.get("nonce"),(String)outbox.get("key_id"));
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
   http(client,base,"POST","/api/auth/logout",null,token,204);
  }
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
 private java.net.http.HttpResponse<String> http(java.net.http.HttpClient client,String base,String method,
      String path,String body,String token,int status) throws Exception {
  return httpVersion(client,base,method,path,body,token,null,status);
 }
 private java.net.http.HttpResponse<String> httpVersion(java.net.http.HttpClient client,String base,String method,
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
 private static final class TestDatabase implements AutoCloseable {
  private PostgreSQLContainer<?> container;
  private Path binaryDirectory, dataDirectory, log;
  private int port;
  private final String username;
  private final String password="isolated-admin-password";
  TestDatabase(boolean legacy) throws Exception {
   username=legacy?"campus":"campus_admin";
   String binaries=System.getProperty("postgres.test.bin");
   if(binaries==null) {
    container=new PostgreSQLContainer<>("postgres:17").withDatabaseName("campus").withUsername(username).withPassword(password)
     .withEnv("MIGRATION_DATABASE_PASSWORD","migration-'quote-password").withEnv("RUNTIME_DATABASE_PASSWORD","runtime-'quote-password");
    container.start();
    container.copyFileToContainer(MountableFile.forHostPath(Path.of("../deploy/postgres").toAbsolutePath()),"/scripts");
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
  String getJdbcUrl(){return container!=null?container.getJdbcUrl():"jdbc:postgresql://127.0.0.1:"+port+"/campus";}
  String getUsername(){return username;}
  String getPassword(){return password;}
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
 private record Result(int getExitCode,String getStderr) {}
}
