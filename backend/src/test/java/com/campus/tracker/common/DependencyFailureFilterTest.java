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
}
