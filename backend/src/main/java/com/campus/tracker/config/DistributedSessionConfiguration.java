package com.campus.tracker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@Profile("distributed")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 7200,
        redisNamespace = "${spring.session.redis.namespace}")
public class DistributedSessionConfiguration {
    @Bean org.springframework.beans.factory.InitializingBean sharedSessionPolicy(
            org.springframework.core.env.Environment environment,
            org.springframework.boot.autoconfigure.data.redis.RedisProperties redis,
            @Value("${spring.session.redis.namespace}") String namespace) {
        return () -> {
            if (!namespace.matches("[a-zA-Z0-9:_-]{1,120}")) throw new IllegalArgumentException("Invalid session namespace");
            if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))
                    && (redis.getPassword() == null || redis.getPassword().isBlank() || namespace.contains(":local:"))) {
                throw new IllegalArgumentException("Production shared sessions require Redis credentials and a deployment namespace");
            }
        };
    }
    @Bean RedisSerializer<Object> springSessionDefaultRedisSerializer() { return new SessionValueSerializer(); }

    @Bean org.springframework.session.web.http.HttpSessionIdResolver httpSessionIdResolver(DefaultCookieSerializer cookies) {
        var delegate = new org.springframework.session.web.http.CookieHttpSessionIdResolver();
        delegate.setCookieSerializer(cookies);
        return new org.springframework.session.web.http.HttpSessionIdResolver() {
            @Override public java.util.List<String> resolveSessionIds(jakarta.servlet.http.HttpServletRequest request) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                // Framework request inspection may call getSession(false), even on a stateless security chain.
                if ("GET".equals(request.getMethod()) && (path.equals("/livez") || path.equals("/readyz"))) return java.util.List.of();
                return delegate.resolveSessionIds(request);
            }
            @Override public void setSessionId(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, String id) {
                delegate.setSessionId(request, response, id);
            }
            @Override public void expireSession(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
                delegate.expireSession(request, response);
            }
        };
    }

    @Bean DefaultCookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("JSESSIONID");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        return cookie;
    }
}
