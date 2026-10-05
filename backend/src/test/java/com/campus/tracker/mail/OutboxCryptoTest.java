package com.campus.tracker.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OutboxCryptoTest {
    @Test void rotationRetainsAnExplicitOldDecryptKeyWhileNewJobsUseTheActiveKey() {
        UUID job = UUID.randomUUID();
        var old = new OutboxCrypto(properties(), new ObjectMapper());
        var payload = old.encrypt(job, "student@example.test", "old-queued-token");
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("rotation", java.util.Map.of(
                "campus.mail.public-origin", "http://localhost:8080", "campus.mail.sender", "capture@example.test",
                "campus.mail.key-id", "new-key", "campus.mail.encryption-key", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                "campus.mail.decryption-keys.local-test", properties().encryptionKey())));
        var rotatedProperties = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("campus.mail", org.springframework.boot.context.properties.bind.Bindable.of(MailProperties.class)).get();
        var rotated = new OutboxCrypto(rotatedProperties, new ObjectMapper());
        assertThat(rotated.decrypt(job, payload.payload(), payload.nonce(), payload.keyId()).token()).isEqualTo("old-queued-token");
        assertThat(rotated.encrypt(UUID.randomUUID(), "student@example.test", "new-token").keyId()).isEqualTo("new-key");
    }
    static MailProperties properties() {
        return new MailProperties("http://localhost:8080", "capture@example.test", "local-test",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", false, false);
    }

    @Test void encryptsSecretsAndAuthenticatesPayloadAndJobIdentity() {
        var crypto = new OutboxCrypto(properties(), new ObjectMapper());
        UUID job = UUID.randomUUID();
        var encrypted = crypto.encrypt(job, "student@example.test", "synthetic-secret-token");
        assertThat(encrypted.payload()).doesNotContain("student", "synthetic-secret-token");
        assertThat(crypto.decrypt(job, encrypted.payload(), encrypted.nonce(), encrypted.keyId()))
                .isEqualTo(new OutboxCrypto.Payload("student@example.test", "synthetic-secret-token"));
        assertThat(crypto.encrypt(job, "student@example.test", "synthetic-secret-token").payload())
                .isNotEqualTo(encrypted.payload());
        assertThatThrownBy(() -> crypto.decrypt(UUID.randomUUID(), encrypted.payload(), encrypted.nonce(), encrypted.keyId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Outbox payload cannot be decrypted");
        String tampered = (encrypted.payload().startsWith("A") ? "B" : "A") + encrypted.payload().substring(1);
        assertThatThrownBy(() -> crypto.decrypt(job, tampered, encrypted.nonce(), encrypted.keyId()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(job, encrypted.payload(), encrypted.nonce(), "unknown-key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void productionRejectsInsecureOriginsAndLocalKeys() {
        String externalTestKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
        assertThat(new MailProperties("https://example.test", "sender@example.test", "prod-v1", externalTestKey, true, true)
                .publicOrigin()).isEqualTo("https://example.test");
        assertThatThrownBy(() -> new MailProperties("http://example.test", "sender@example.test", "prod", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MailProperties("https://example.test/path", "sender@example.test", "prod", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MailProperties("https://example.test", "sender@example.test", "local-capture-only", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void configurationDiagnosticsDoNotExposeEncryptionKeys() {
        assertThat(properties().toString()).doesNotContain("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }
}
