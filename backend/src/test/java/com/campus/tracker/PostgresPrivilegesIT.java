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
    Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load().migrate();
    try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) {
     s.execute("INSERT INTO campus_users VALUES ('00000000-0000-0000-0000-000000000001','legacy@example.test','Legacy','hash',CURRENT_TIMESTAMP)");
    }
   }
   var provision=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f",""+(legacy?"upgrade-existing.sql":"provision.sql"));
   assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator","MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   // Repeat deployment: migrations and grants are idempotent.
   MigrationMain.run(Map.of("MIGRATION_DATABASE_URL",db.getJdbcUrl(),"MIGRATION_DATABASE_USERNAME","campus_migrator","MIGRATION_DATABASE_PASSWORD","migration-'quote-password"));
   try(var c=DriverManager.getConnection(db.getJdbcUrl(),"campus_runtime","runtime-'quote-password");var s=c.createStatement()) {
    if(legacy) {try(var r=s.executeQuery("SELECT display_name FROM campus_users")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("Legacy");}}
    s.execute("INSERT INTO campus_users VALUES ('00000000-0000-0000-0000-000000000002','new@example.test','New','hash',CURRENT_TIMESTAMP)");
    s.execute("INSERT INTO applications(id,owner_id,company,role,location,status,notes,created_at,updated_at) VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','Company','Role','Remote','SAVED','',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    s.execute("UPDATE applications SET deleted=TRUE,version=version+1");
    s.execute("UPDATE applications SET deleted=FALSE,version=version+1");
    s.execute("INSERT INTO interview_stages VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003',0,'Interview',NULL,'PLANNED')");
    s.execute("UPDATE interview_stages SET name='Changed'");
    assertThat(s.executeUpdate("DELETE FROM interview_stages")).isEqualTo(1);
    for(String denied : new String[]{"CREATE TABLE public.forbidden_probe(id int)","CREATE TEMP TABLE forbidden_temp(id int)","CREATE SCHEMA forbidden_schema","CREATE ROLE forbidden_role","CREATE DATABASE forbidden_db","DROP TABLE applications","ALTER TABLE applications ADD COLUMN forbidden text","TRUNCATE applications","DELETE FROM applications","DELETE FROM campus_users","UPDATE campus_users SET display_name='forbidden'","SELECT * FROM flyway_schema_history","SET ROLE campus_migrator","SET ROLE "+db.getUsername()}) {
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
          "--debug=false","--logging.level.root=WARN")) {
   var server=(org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context;
   String base="http://127.0.0.1:"+server.getWebServer().getPort();
   var cookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
   var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
   var json=new com.fasterxml.jackson.databind.ObjectMapper();
   var csrf=json.readTree(http(client,base,"GET","/api/auth/csrf",null,null,200).body());
   String token=csrf.get("token").asText();
   String email="http-"+java.util.UUID.randomUUID()+"@example.test";
   http(client,base,"POST","/api/auth/register",json.writeValueAsString(Map.of(
       "displayName","Runtime Test","email",email,"password","Campus-test-123!","includeSamples",true)),token,201);
   http(client,base,"POST","/api/auth/login","email="+java.net.URLEncoder.encode(email,java.nio.charset.StandardCharsets.UTF_8)
       +"&password=Campus-test-123%21",token,200);
   token=json.readTree(http(client,base,"GET","/api/auth/csrf",null,null,200).body()).get("token").asText();
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