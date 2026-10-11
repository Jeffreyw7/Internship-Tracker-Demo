package com.campus.tracker.auth;

import com.campus.tracker.CampusApplication;
import com.campus.tracker.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Each probe is a fresh JVM: a previous static policy initialization cannot hide a bad ENV value. */
class PasswordPolicyStartupTest {
    @TempDir Path directory;

    @Test void missingSupplementalBlocklistRefusesStartupWithoutDisclosingItsPath() throws Exception {
        String privateName = "policy-private-marker-" + UUID.randomUUID() + ".txt";
        Result result = launch(directory.resolve(privateName), false);
        assertThat(result.exit()).isNotZero();
        assertThat(result.output()).contains("Password blocklist cannot be loaded.")
                .doesNotContain("PASSWORD_POLICY_STARTUP_READY", privateName);
    }

    @Test void validSupplementAllowsStartupAndIsAlreadyEnforcedForPasswordCreation() throws Exception {
        Path supplement = directory.resolve("supplement.txt");
        Files.writeString(supplement, "startup-isolated-blocked-passphrase\n", StandardCharsets.UTF_8);
        Result result = launch(supplement, true);
        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).contains("PASSWORD_POLICY_STARTUP_READY", "SUPPLEMENTAL_PASSWORD_REJECTED");
    }

    private Result launch(Path supplement, boolean verifySupplement) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var arguments = new ArrayList<String>();
        arguments.add("-cp"); arguments.add(classpath.replace('\\','/')); arguments.add(Probe.class.getName());
        arguments.add("--server.address=127.0.0.1"); arguments.add("--server.port=0");
        arguments.add("--spring.profiles.active=test"); arguments.add("--spring.datasource.username=sa");
        arguments.add("--spring.datasource.password=");
        arguments.add("--spring.datasource.url=jdbc:h2:mem:policy-startup-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        arguments.add("--spring.flyway.enabled=true"); arguments.add("--campus.mail.worker-enabled=false");
        arguments.add("--campus.reset.worker-enabled=false"); arguments.add("--campus.registration-enabled=false");
        arguments.add("--campus.mail.production=false"); arguments.add("--campus.mail.public-origin=http://127.0.0.1:8080");
        arguments.add("--campus.mail.sender=capture@example.test"); arguments.add("--campus.mail.key-id=local-policy-probe");
        arguments.add("--campus.mail.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        arguments.add("--campus.verification.rate-limit-secret=isolated-policy-probe-rate-secret");
        arguments.add("--spring.main.banner-mode=off"); arguments.add("--logging.level.root=ERROR");
        arguments.add("--policy.probe.verify-supplement="+verifySupplement);
        Path argumentFile = directory.resolve("java-"+UUID.randomUUID()+".args");
        var quoted = arguments.stream().map(value -> "\""+value.replace("\"","\\\"")+"\"").toList();
        Files.write(argumentFile,quoted,StandardCharsets.UTF_8);
        Path log = directory.resolve("probe-"+UUID.randomUUID()+".log");
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"@"+argumentFile);
        builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("PASSWORD_BLOCKLIST_FILE",supplement.toString());
        Process process = builder.start();
        boolean exited = process.waitFor(45,TimeUnit.SECONDS);
        if(!exited) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); }
        String output = Files.readString(log,StandardCharsets.UTF_8);
        assertThat(exited).as("Isolated policy startup did not terminate: %s",output).isTrue();
        return new Result(process.exitValue(),output);
    }
    private record Result(int exit,String output) { }

    public static class Probe {
        public static void main(String[] args) {
            try(var context = SpringApplication.run(CampusApplication.class,args)) {
                System.out.println("PASSWORD_POLICY_STARTUP_READY");
                if(context.getEnvironment().getProperty("policy.probe.verify-supplement",Boolean.class,false)) {
                    try {
                        PasswordPolicy.validate("startup-isolated-blocked-passphrase");
                        throw new IllegalStateException("Supplemental password unexpectedly admitted");
                    } catch(ApiException rejected) { System.out.println("SUPPLEMENTAL_PASSWORD_REJECTED"); }
                }
            }
        }
    }
}
