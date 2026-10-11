package com.campus.tracker.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OutboxCrypto {
    private final MailProperties properties;
    private final ObjectMapper json;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom random = new SecureRandom();

    public OutboxCrypto(MailProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        var configured = new HashMap<String, SecretKeySpec>();
        properties.decryptKeys().forEach((id, value) -> configured.put(id, new SecretKeySpec(Base64.getDecoder().decode(value), "AES")));
        configured.put(properties.keyId(), new SecretKeySpec(Base64.getDecoder().decode(properties.encryptionKey()), "AES"));
        this.keys = Map.copyOf(configured);
    }
    public record Payload(String recipient, String token) {
        @Override public String toString() { return "Payload[redacted]"; }
    }
    public record EncryptedPayload(String payload, String nonce, String keyId) { }
    public record MessagePayload(int version, EmailMessage.Type type, String recipient, String token, String occurredAt) {
        @Override public String toString() { return "MessagePayload[version=" + version + ", type=" + type + "]"; }
    }
    private record IntakePayload(int version, String purpose, String email) { }
    public EncryptedPayload encryptIntake(UUID job, String email) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            byte[] clear = json.writeValueAsBytes(new IntakePayload(1, "PASSWORD_RESET_INTAKE", email));
            byte[] encrypted = purposeCipher(Cipher.ENCRYPT_MODE, job, nonce, properties.keyId(), "PASSWORD_RESET_INTAKE").doFinal(clear);
            return new EncryptedPayload(Base64.getEncoder().encodeToString(encrypted), Base64.getEncoder().encodeToString(nonce), properties.keyId());
        } catch (Exception exception) { throw new IllegalStateException("Recovery intake cannot be encrypted"); }
    }
    public String decryptIntake(UUID job, String payload, String nonce, String keyId) {
        try {
            byte[] clear = purposeCipher(Cipher.DECRYPT_MODE, job, Base64.getDecoder().decode(nonce), keyId, "PASSWORD_RESET_INTAKE")
                    .doFinal(Base64.getDecoder().decode(payload));
            IntakePayload result = json.readValue(clear, IntakePayload.class);
            if (result.version() != 1 || !"PASSWORD_RESET_INTAKE".equals(result.purpose()) || result.email() == null) throw new IllegalArgumentException();
            return result.email();
        } catch (Exception exception) { throw new IllegalStateException("Recovery intake cannot be decrypted"); }
    }
    public EncryptedPayload encrypt(UUID job, EmailMessage.Type type, String recipient, String token, Instant occurredAt) {
        try {
            if (recipient == null || type == null || (type == EmailMessage.Type.PASSWORD_CHANGED
                    ? token != null || occurredAt == null : token == null)) throw new IllegalArgumentException();
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, job, nonce, properties.keyId(), type);
            var clear = new MessagePayload(1, type, recipient, token, occurredAt == null ? null : occurredAt.toString());
            return new EncryptedPayload(Base64.getEncoder().encodeToString(cipher.doFinal(json.writeValueAsBytes(clear))),
                    Base64.getEncoder().encodeToString(nonce), properties.keyId());
        } catch (Exception exception) { throw new IllegalStateException("Outbox payload cannot be encrypted"); }
    }
    public MessagePayload decrypt(UUID job, EmailMessage.Type type, String payload, String nonce, String keyId) {
        try {
            byte[] clear;
            try { clear = cipher(Cipher.DECRYPT_MODE, job, Base64.getDecoder().decode(nonce), keyId, type)
                    .doFinal(Base64.getDecoder().decode(payload)); }
            catch (javax.crypto.AEADBadTagException legacyTag) {
                if (type != EmailMessage.Type.EMAIL_VERIFICATION) throw legacyTag;
                var old = decrypt(job, payload, nonce, keyId);
                return new MessagePayload(0, type, old.recipient(), old.token(), null);
            }
            var result = json.readValue(clear, MessagePayload.class);
            if (result.version() != 1 || result.type() != type || result.recipient() == null
                    || (type == EmailMessage.Type.PASSWORD_CHANGED ? result.token() != null || result.occurredAt() == null : result.token() == null))
                throw new IllegalArgumentException();
            if (result.occurredAt() != null) Instant.parse(result.occurredAt());
            return result;
        } catch (Exception exception) { throw new IllegalStateException("Outbox payload cannot be decrypted"); }
    }
    public EncryptedPayload encrypt(UUID job, String recipient, String token) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, job, nonce, properties.keyId());
            return new EncryptedPayload(Base64.getEncoder().encodeToString(cipher.doFinal(json.writeValueAsBytes(new Payload(recipient, token)))),
                    Base64.getEncoder().encodeToString(nonce), properties.keyId());
        } catch (Exception exception) {
            throw new IllegalStateException("Outbox payload cannot be encrypted");
        }
    }

    public Payload decrypt(UUID job, String payload, String nonce, String keyId) {
        try {
            if (!keys.containsKey(keyId)) throw new IllegalArgumentException();
            byte[] clear = cipher(Cipher.DECRYPT_MODE, job, Base64.getDecoder().decode(nonce), keyId)
                    .doFinal(Base64.getDecoder().decode(payload));
            return json.readValue(clear, Payload.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Outbox payload cannot be decrypted");
        }
    }

    private Cipher cipher(int mode, UUID job, byte[] nonce, String keyId) throws Exception {
        return cipher(mode, job, nonce, keyId, null);
    }
    private Cipher cipher(int mode, UUID job, byte[] nonce, String keyId, EmailMessage.Type type) throws Exception {
        return purposeCipher(mode, job, nonce, keyId, type == null ? null : type.name());
    }
    private Cipher purposeCipher(int mode, UUID job, byte[] nonce, String keyId, String purpose) throws Exception {
        if (nonce.length != 12) throw new IllegalArgumentException();
        SecretKeySpec key = keys.get(keyId);
        if (key == null) throw new IllegalArgumentException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD((job + ":" + keyId + (purpose == null ? "" : ":1:" + purpose)).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
