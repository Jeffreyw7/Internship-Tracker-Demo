package com.campus.tracker.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OutboxCryptoTest {
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
