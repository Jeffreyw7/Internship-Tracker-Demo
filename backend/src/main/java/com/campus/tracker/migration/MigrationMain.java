package com.campus.tracker.migration;

import org.flywaydb.core.Flyway;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Map;

/** One-shot entry point: deliberately does not bootstrap Spring, JPA, or HTTP. */
public final class MigrationMain {
 private MigrationMain() {}
 public static void main(String[] args) {
  try { run(System.getenv()); }
  catch (Exception failure) {
   // Driver errors may include connection details. Keep secrets out of container logs.
   System.err.println("Database migration or runtime grants failed; application startup must remain blocked.");
   System.exit(1);
  }
 }
 public static void run(Map<String,String> environment) throws Exception {
  String url=required(environment,"MIGRATION_DATABASE_URL");
  String user=required(environment,"MIGRATION_DATABASE_USERNAME");
  String password=required(environment,"MIGRATION_DATABASE_PASSWORD");
  if(!"campus_migrator".equals(user)) throw new IllegalArgumentException("Migrations require campus_migrator credentials");
  if(!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Migration URL must use PostgreSQL");
  Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").loggers().load().migrate();
  try(var script=MigrationMain.class.getResourceAsStream("/db/security/runtime-grants.sql")) {
   if(script==null) throw new IllegalStateException("Packaged runtime grants are missing");
   String sql=new String(script.readAllBytes(),StandardCharsets.UTF_8);
   try(var connection=DriverManager.getConnection(url,user,password)) {
    connection.setAutoCommit(false);
    try(var statement=connection.createStatement()) {statement.execute(sql);connection.commit();}
    catch(Exception failure) {connection.rollback();throw failure;}
   }
  }
 }
 private static String required(Map<String,String> environment,String name) {
  String value=environment.get(name);
  if(value==null || value.isBlank()) throw new IllegalArgumentException("Missing "+name);
  return value;
 }
}
