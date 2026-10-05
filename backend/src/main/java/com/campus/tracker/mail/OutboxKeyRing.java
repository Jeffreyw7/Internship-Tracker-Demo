package com.campus.tracker.mail;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Encrypt with one active key; decrypt only explicitly retained IDs during rotation. */
@Component
public final class OutboxKeyRing {
    private final String activeId;
    private final Map<String, SecretKeySpec> keys;

    public OutboxKeyRing(MailProperties properties) {
        try {
            if (properties.decryptionKeys().size() > 7) throw new IllegalArgumentException();
            var configured = new HashMap<>(properties.decryptionKeys());
            String previous = configured.put(properties.keyId(), properties.encryptionKey());
            if (previous != null && !previous.equals(properties.encryptionKey())) throw new IllegalArgumentException();
            var built = new HashMap<String, SecretKeySpec>();
            configured.forEach((id, encoded) -> {
                if (id == null || !id.matches("[a-zA-Z0-9_.:-]{1,100}") || encoded == null
                        || Base64.getDecoder().decode(encoded).length != 32
                        || (properties.production() && (id.startsWith("local")
                                || encoded.equals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")))) throw new IllegalArgumentException();
                built.put(id, new SecretKeySpec(Base64.getDecoder().decode(encoded), "AES"));
            });
            activeId = properties.keyId();
            keys = Map.copyOf(built);
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid outbox keyring configuration"); }
    }
    public String activeId() { return activeId; }
    public SecretKeySpec key(String id) {
        SecretKeySpec result = keys.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown outbox key ID");
        return result;
    }
    @Override public String toString() { return "OutboxKeyRing[redacted]"; }
}
