package com.campus.tracker.config;

import com.campus.tracker.auth.CampusPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import static org.assertj.core.api.Assertions.*;

class SessionValueSerializerTest {
    private final SessionValueSerializer serializer = new SessionValueSerializer();

    @Test void independentSerializerInstancesRoundTripOnlySanitizedIdentity() {
        var principal = new CampusPrincipal(UUID.randomUUID(), "Member", "member@example.test", null, true);
        var context = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        byte[] bytes = serializer.serialize(context);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("password", "credentials", "@class");
        var restored = (SecurityContextImpl) new SessionValueSerializer().deserialize(bytes);
        assertThat(restored.getAuthentication().getPrincipal()).isEqualTo(principal);
        assertThat(restored.getAuthentication().getCredentials()).isNull();
        assertThat(restored.getAuthentication().isAuthenticated()).isTrue();
        assertThat(restored.getAuthentication().getAuthorities()).extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test void metadataAndCsrfRemainCompatible() {
        for (Object value : new Object[]{42L, 7200, "value"})
            assertThat(serializer.deserialize(serializer.serialize(value))).isEqualTo(value);
        var csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString());
        var restored = (DefaultCsrfToken) serializer.deserialize(serializer.serialize(csrf));
        assertThat(restored.getToken()).isEqualTo(csrf.getToken());
        assertThat(restored.getHeaderName()).isEqualTo(csrf.getHeaderName());
        assertThat(serializer.deserialize(null)).isNull();
        assertThat(serializer.deserialize(new byte[0])).isNull();
        assertThat(((SecurityContextImpl) serializer.deserialize(serializer.serialize(new SecurityContextImpl()))).getAuthentication()).isNull();
    }

    @Test void malformedUnknownAndOversizedValuesFailClosed() {
        for (String value : new String[]{"{}", "{\"v\":2,\"t\":\"long\",\"value\":42}",
                "{\"v\":1,\"t\":\"java.lang.Runtime\",\"value\":{}}",
                "{\"v\":1,\"t\":\"security\",\"value\":{\"id\":\"bad\",\"name\":\"User\",\"email\":\"user@example.test\"}}",
                "{\"v\":1,\"t\":\"int\",\"value\":999999999999}",
                "{\"v\":1,\"t\":\"long\",\"value\":42} {\"extra\":true}",
                "{\"v\":1,\"t\":\"long\",\"value\":42,\"value\":43}", "x".repeat(16385)}) {
            assertThatThrownBy(() -> serializer.deserialize(value.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(SerializationException.class).hasMessage("Invalid or unsupported session data");
        }
        var unsafe = new CampusPrincipal(UUID.randomUUID(), "User", "user@example.test", "private-hash", true);
        var context = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(unsafe, null, unsafe.getAuthorities()));
        assertThatThrownBy(() -> serializer.serialize(context)).isInstanceOf(SerializationException.class);
        assertThatThrownBy(() -> serializer.serialize(new Object())).isInstanceOf(SerializationException.class);
    }
}
