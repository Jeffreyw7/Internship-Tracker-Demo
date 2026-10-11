package com.campus.tracker.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class RecoveryMailTest {
    @Test void intakeEncryptionIsSeparateFromTokenBearingMailAndBoundToItsJob() {
        var crypto = new OutboxCrypto(OutboxCryptoTest.properties(), new ObjectMapper());
        UUID job = UUID.randomUUID();
        var encrypted = crypto.encryptIntake(job, "student@example.test");
        assertThat(crypto.decryptIntake(job, encrypted.payload(), encrypted.nonce(), encrypted.keyId())).isEqualTo("student@example.test");
        assertThatThrownBy(() -> crypto.decryptIntake(UUID.randomUUID(), encrypted.payload(), encrypted.nonce(), encrypted.keyId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(job, EmailMessage.Type.PASSWORD_RESET_LINK, encrypted.payload(), encrypted.nonce(), encrypted.keyId())).isInstanceOf(IllegalStateException.class);
    }
    @Test void typedCiphertextCannotBeReinterpretedAsAnotherMailPurpose() {
        var crypto = new OutboxCrypto(OutboxCryptoTest.properties(), new ObjectMapper());
        UUID job = UUID.randomUUID();
        var encrypted = crypto.encrypt(job, EmailMessage.Type.PASSWORD_RESET_LINK, "student@example.test", "reset-secret", null);
        var decoded = crypto.decrypt(job, EmailMessage.Type.PASSWORD_RESET_LINK, encrypted.payload(), encrypted.nonce(), encrypted.keyId());
        assertThat(decoded.recipient()).isEqualTo("student@example.test");
        assertThat(decoded.token()).isEqualTo("reset-secret");
        assertThatThrownBy(() -> crypto.decrypt(job, EmailMessage.Type.PASSWORD_CHANGED, encrypted.payload(), encrypted.nonce(), encrypted.keyId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void keyRotationReadsLegacyVerificationAndTypedNoticeWithoutIncludingToken() {
        var oldCrypto = new OutboxCrypto(OutboxCryptoTest.properties(), new ObjectMapper());
        UUID legacyJob = UUID.randomUUID();
        var legacy = oldCrypto.encrypt(legacyJob, "student@example.test", "verification-secret");
        var rotated = new OutboxCrypto(new MailProperties("http://localhost:8080", "capture@example.test", "next-key",
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", false, false,
                Map.of("local-test", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), null), new ObjectMapper());
        assertThat(rotated.decrypt(legacyJob, EmailMessage.Type.EMAIL_VERIFICATION, legacy.payload(), legacy.nonce(), legacy.keyId()).token())
                .isEqualTo("verification-secret");
        assertThatThrownBy(() -> rotated.decrypt(legacyJob, EmailMessage.Type.PASSWORD_RESET_LINK, legacy.payload(), legacy.nonce(), legacy.keyId()))
                .isInstanceOf(IllegalStateException.class);
        UUID noticeJob = UUID.randomUUID();
        var notice = rotated.encrypt(noticeJob, EmailMessage.Type.PASSWORD_CHANGED, "student@example.test", null, Instant.parse("2026-10-11T12:00:00Z"));
        var decoded = rotated.decrypt(noticeJob, EmailMessage.Type.PASSWORD_CHANGED, notice.payload(), notice.nonce(), notice.keyId());
        assertThat(decoded.token()).isNull();
        assertThat(decoded.occurredAt()).isEqualTo("2026-10-11T12:00:00Z");
    }

    @Test void productionRequiresAnExplicitTrustedHelpDestination() {
        assertThatThrownBy(() -> new MailProperties("https://example.test", "sender@example.test", "prod-v1",
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", true, true, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
