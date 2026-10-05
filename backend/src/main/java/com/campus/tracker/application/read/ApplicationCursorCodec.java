package com.campus.tracker.application.read;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Cursor boundaries are untrusted query values, never an authorization source. */
@Component
public class ApplicationCursorCodec {
    private final ObjectMapper json;
    public ApplicationCursorCodec(ObjectMapper json) {
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    public record Boundary(int version, String fingerprint, String value, UUID id) {}
    public String fingerprint(UUID owner, Object... filters) {
        try {
            byte[] bytes = json.writeValueAsBytes(new Object[]{owner, filters});
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) { throw new IllegalStateException("Cannot fingerprint read filters", failure); }
    }
    public String encode(String fingerprint, String value, UUID id) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(new Boundary(1, fingerprint, value, id))); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode read cursor", failure); }
    }
    public Boundary decode(String cursor, String fingerprint) {
        if (cursor == null) return null;
        if (cursor.isBlank() || cursor.length() > 2048 || !cursor.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid cursor");
        try {
            var boundary = json.readValue(Base64.getUrlDecoder().decode(cursor), Boundary.class);
            if (boundary.version() != 1 || !fingerprint.equals(boundary.fingerprint()) || boundary.id() == null
                    || (boundary.value() != null && boundary.value().length() > 120)) throw new IllegalArgumentException("Invalid cursor");
            return boundary;
        } catch (Exception failure) { throw new IllegalArgumentException("Invalid cursor"); }
    }
}
