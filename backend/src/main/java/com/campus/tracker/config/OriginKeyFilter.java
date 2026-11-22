package com.campus.tracker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

final class OriginKeyFilter extends OncePerRequestFilter {
    private final byte[] key;

    OriginKeyFilter(String key) {
        if (!key.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("STAGING_ORIGIN_KEY must contain 32 to 128 URL-safe characters");
        }
        this.key = key.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        boolean health = ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && (request.getContextPath() + "/api/health").equals(request.getRequestURI());
        var values = request.getHeaders("X-Campus-Origin-Key");
        String supplied = values.hasMoreElements() ? values.nextElement() : "";
        if (health || (!values.hasMoreElements() && supplied.length() == key.length
                && MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8)))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader("Cache-Control", "no-store");
    }
}
