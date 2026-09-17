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
   var verification=db.psql("psql","-X","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d","campus","-f","verify-runtime.sql");
   assertThat(verification.getExitCode()).withFailMessage(verification.getStderr()).isZero();
  }
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
   var process=builder.start();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   return new Result(process.waitFor(),output);
  }
  private void check(Result r){assertThat(r.getExitCode()).withFailMessage(r.getStderr()).isZero();}
  public void close() throws Exception {
   if(container!=null) container.close();
   else if(dataDirectory!=null) check(process("pg_ctl","-D",dataDirectory.toString(),"-m","fast","-w","stop"));
  }
 }
 private record Result(int getExitCode,String getStderr) {}
}