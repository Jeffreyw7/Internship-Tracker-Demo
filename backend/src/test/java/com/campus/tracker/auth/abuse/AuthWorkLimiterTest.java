package com.campus.tracker.auth.abuse;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuthWorkLimiterTest {
    @Test void boundsWorkAndReleasesExactlyOnce() {
        var work = new AuthWorkLimiter(1);
        var permit = work.tryAcquire();
        assertThat(permit).isNotNull();
        assertThat(work.tryAcquire()).isNull();
        permit.close();
        permit.close();
        try (var next = work.tryAcquire()) {
            assertThat(next).isNotNull();
            assertThat(work.tryAcquire()).isNull();
        }
    }
    @Test void exceptionalWorkDoesNotLeakPermits() {
        var work = new AuthWorkLimiter(1);
        assertThatThrownBy(() -> {
            try (var permit = work.tryAcquire()) { throw new IllegalStateException("synthetic"); }
        }).isInstanceOf(IllegalStateException.class);
        try (var next = work.tryAcquire()) { assertThat(next).isNotNull(); }
    }
}
