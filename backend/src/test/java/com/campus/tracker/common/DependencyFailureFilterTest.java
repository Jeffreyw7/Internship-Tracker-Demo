package com.campus.tracker.common;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class DependencyFailureFilterTest {
    @Test void unavailableConnectionReturnsSafeRetryableResponse() throws Exception {
        var response = new MockHttpServletResponse();
        new DependencyFailureFilter().doFilter(new MockHttpServletRequest(), response,
                (request, result) -> { throw new CannotGetJdbcConnectionException("private endpoint and credentials"); });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("credentials");
    }

    @Test void committedResponsesCannotBeReplaced() throws Exception {
        var response = new MockHttpServletResponse();
        var failure = new CannotGetJdbcConnectionException("unavailable");
        assertThatThrownBy(() -> new DependencyFailureFilter().doFilter(new MockHttpServletRequest(), response,
                (request, result) -> {
                    result.getWriter().write("already sent"); result.flushBuffer(); throw failure;
                })).isSameAs(failure);
        assertThat(response.getContentAsString()).isEqualTo("already sent");
    }

    @Test void programmingErrorsAreNotMisreportedAsInfrastructureFailure() {
        var failure = new IllegalStateException("bug");
        assertThatThrownBy(() -> new DependencyFailureFilter().doFilter(new MockHttpServletRequest(),
                new MockHttpServletResponse(), (request, result) -> { throw failure; })).isSameAs(failure);
    }
    @Test void corruptedSharedSessionIsUnavailableWithoutLocalFallback() throws Exception {
        var response = new MockHttpServletResponse();
        new DependencyFailureFilter().doFilter(new MockHttpServletRequest(), response,
                (request, result) -> { throw new org.springframework.data.redis.serializer.SerializationException("private session payload"); });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("payload");
    }

    @Test void uncommittedStreamFailureResetsOutputModeAndStaleHeaders() throws Exception {
        // MockHttpServletResponse allows both accessors; a real servlet container forbids mixing them until reset().
        var response = new MockHttpServletResponse() {
            private boolean streamSelected;
            @Override public jakarta.servlet.ServletOutputStream getOutputStream() {
                streamSelected = true; return super.getOutputStream();
            }
            @Override public java.io.PrintWriter getWriter() throws java.io.UnsupportedEncodingException {
                if (streamSelected) throw new IllegalStateException("getOutputStream already selected");
                return super.getWriter();
            }
            @Override public void reset() { super.reset(); streamSelected = false; }
        };
        response.setHeader("X-Request-ID", "trace-outage");
        new DependencyFailureFilter().doFilter(new MockHttpServletRequest(), response, (request, result) -> {
            response.setHeader("Content-Length", "99999");
            response.setHeader("Content-Disposition", "attachment; filename=private.csv");
            response.addHeader("Set-Cookie", "JSESSIONID=unpersisted; HttpOnly");
            result.getOutputStream().write("partial private response".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw new org.springframework.data.redis.RedisSystemException("Session save rejected", new IllegalStateException("OOM"));
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("trace-outage");
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getHeader("Content-Disposition")).isNull();
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(response.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("private response");
    }
}
