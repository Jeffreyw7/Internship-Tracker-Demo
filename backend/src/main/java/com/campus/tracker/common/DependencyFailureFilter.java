package com.campus.tracker.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Unavailable infrastructure is a retryable service failure, never an authentication failure. */
public final class DependencyFailureFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try { chain.doFilter(request, response); }
        catch (ServletException | RuntimeException failure) {
            if (!isDependencyFailure(failure) || response.isCommitted()) throw failure;
            String correlation = response.getHeader("X-Request-ID");
            // resetBuffer leaves the writer/stream choice and stale content/cookie headers intact.
            response.reset();
            if (correlation != null) response.setHeader("X-Request-ID", correlation);
            response.setStatus(503);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Retry-After", "3");
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Service temporarily unavailable.\",\"code\":\"DEPENDENCY_UNAVAILABLE\"}");
        }
    }

    private boolean isDependencyFailure(Throwable failure) {
        for (int depth = 0; failure != null && depth < 20; depth++, failure = failure.getCause()) {
            if (failure instanceof DataAccessResourceFailureException
                    || failure instanceof org.springframework.dao.QueryTimeoutException
                    || failure instanceof org.springframework.data.redis.RedisSystemException
                    || failure instanceof org.springframework.data.redis.serializer.SerializationException
                    || failure instanceof org.hibernate.exception.JDBCConnectionException) return true;
        }
        return false;
    }
}
