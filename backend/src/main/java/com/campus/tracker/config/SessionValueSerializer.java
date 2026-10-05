package com.campus.tracker.config;

import com.campus.tracker.auth.CampusPrincipal;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** A fixed JSON protocol: no Java deserialization, polymorphic class names, or password hashes. */
public final class SessionValueSerializer implements RedisSerializer<Object> {
    private static final int MAX_BYTES = 16_384;
    private final ObjectMapper json = new ObjectMapper();

    public SessionValueSerializer() {
        json.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.getFactory().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(8).maxStringLength(10_000).maxNumberLength(24).build());
    }

    @Override public byte[] serialize(Object value) throws SerializationException {
        if (value == null) return new byte[0];
        try {
            ObjectNode envelope = json.createObjectNode().put("v", 1);
            if (value instanceof Long number) envelope.put("t", "long").put("value", number);
            else if (value instanceof Integer number) envelope.put("t", "int").put("value", number);
            else if (value instanceof String text) envelope.put("t", "string").put("value", text);
            else if (value instanceof DefaultCsrfToken csrf) {
                envelope.put("t", "csrf").putObject("value").put("header", csrf.getHeaderName())
                        .put("parameter", csrf.getParameterName()).put("token", csrf.getToken());
            } else if (value instanceof SecurityContext security) {
                envelope.put("t", "security");
                var authentication = security.getAuthentication();
                if (authentication == null) envelope.putNull("value");
                else if (authentication instanceof UsernamePasswordAuthenticationToken
                        && authentication.isAuthenticated() && authentication.getPrincipal() instanceof CampusPrincipal principal
                        && principal.emailAccessAllowed() && principal.passwordHash() == null
                        && authentication.getCredentials() == null) {
                    envelope.putObject("value").put("id", principal.id().toString())
                            .put("name", principal.displayName()).put("email", principal.email());
                } else throw new IllegalArgumentException("Unsupported authenticated session value");
            } else throw new IllegalArgumentException("Unsupported session value type");
            byte[] result = json.writeValueAsBytes(envelope);
            if (result.length > MAX_BYTES) throw new IllegalArgumentException("Session value exceeds limit");
            return result;
        } catch (Exception failure) { throw invalid(); }
    }

    @Override public Object deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) return null;
        try {
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Session value exceeds limit");
            JsonNode root = json.readTree(bytes);
            fields(root, Set.of("v", "t", "value"));
            if (!root.get("v").isInt() || root.get("v").intValue() != 1) throw new IllegalArgumentException("Unknown session version");
            String type = text(root, "t", 16);
            JsonNode value = root.get("value");
            return switch (type) {
                case "long" -> { if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException(); yield value.longValue(); }
                case "int" -> { if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException(); yield value.intValue(); }
                case "string" -> { if (!value.isTextual() || value.textValue().length() > 10_000) throw new IllegalArgumentException(); yield value.textValue(); }
                case "csrf" -> {
                    fields(value, Set.of("header", "parameter", "token"));
                    String header = text(value, "header", 64), parameter = text(value, "parameter", 64);
                    if (!header.equals("X-CSRF-TOKEN") || !parameter.equals("_csrf")) throw new IllegalArgumentException();
                    yield new DefaultCsrfToken(header, parameter, text(value, "token", 256));
                }
                case "security" -> {
                    if (value.isNull()) yield new SecurityContextImpl();
                    fields(value, Set.of("id", "name", "email"));
                    var principal = new CampusPrincipal(UUID.fromString(text(value, "id", 36)),
                            text(value, "name", 80), text(value, "email", 254), null, true);
                    yield new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                            principal, null, principal.getAuthorities()));
                }
                default -> throw new IllegalArgumentException("Unknown session value type");
            };
        } catch (Exception failure) { throw invalid(); }
    }

    private static String text(JsonNode value, String field, int maximum) {
        JsonNode result = value.get(field);
        if (result == null || !result.isTextual() || result.textValue().isBlank()
                || result.textValue().length() > maximum) throw new IllegalArgumentException("Invalid session field");
        return result.textValue();
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw new IllegalArgumentException("Invalid session shape");
        node.fieldNames().forEachRemaining(field -> { if (!expected.contains(field)) throw new IllegalArgumentException("Unknown session field"); });
    }
    private static SerializationException invalid() { return new SerializationException("Invalid or unsupported session data"); }
}
