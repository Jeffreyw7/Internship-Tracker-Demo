package com.campus.tracker.auth.reset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public final class ResetAudit {
    private final byte[] key;
    private final String keyId;
    public ResetAudit(@Value("${campus.reset.audit-secret}") String secret,@Value("${campus.reset.audit-key-id}") String keyId) {
        if (secret.length()<32 || !keyId.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid recovery audit configuration");
        this.key=secret.getBytes(StandardCharsets.UTF_8); this.keyId=keyId;
    }
    public String subject(UUID account) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return keyId+":"+HexFormat.of().formatHex(mac.doFinal(account.toString().getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception failure) { throw new IllegalStateException("Recovery audit unavailable"); }
    }
}
