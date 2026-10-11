package com.campus.tracker.auth.reset;

import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class ResetTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private ResetTokens() {}
    static String create() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static boolean canonical(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            return decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token);
        } catch (IllegalArgumentException invalid) { return false; }
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Token digest unavailable"); }
    }
    static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST,"PASSWORD_RESET_INVALID","This reset link is invalid or no longer available. Request a new link.");
    }
}
