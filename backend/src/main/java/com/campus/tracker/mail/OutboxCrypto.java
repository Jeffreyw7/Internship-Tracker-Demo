package com.campus.tracker.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class OutboxCrypto {
    private final MailProperties properties;
    private final ObjectMapper json;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public OutboxCrypto(MailProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.encryptionKey()), "AES");
    }
    public record Payload(String recipient, String token) { }
    public record EncryptedPayload(String payload, String nonce, String keyId) { }
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
            if (!properties.keyId().equals(keyId)) throw new IllegalArgumentException();
            byte[] clear = cipher(Cipher.DECRYPT_MODE, job, Base64.getDecoder().decode(nonce), keyId)
                    .doFinal(Base64.getDecoder().decode(payload));
            return json.readValue(clear, Payload.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Outbox payload cannot be decrypted");
        }
    }

    private Cipher cipher(int mode, UUID job, byte[] nonce, String keyId) throws Exception {
        if (nonce.length != 12) throw new IllegalArgumentException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD((job + ":" + keyId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
