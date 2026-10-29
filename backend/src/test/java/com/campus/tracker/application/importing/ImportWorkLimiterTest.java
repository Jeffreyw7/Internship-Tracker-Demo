package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ImportWorkLimiterTest {
    @Test void boundsOwnersAndProcessCapacityAndRecoversReleasedPermits() {
        var limiter = new ImportWorkLimiter();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID(), fourth = UUID.randomUUID(), fifth = UUID.randomUUID();
        limiter.acquire(first);
        assertThatThrownBy(() -> limiter.acquire(first)).isInstanceOf(ApiException.class);
        limiter.acquire(second); limiter.acquire(third); limiter.acquire(fourth);
        assertThatThrownBy(() -> limiter.acquire(fifth)).isInstanceOf(ApiException.class);
        limiter.release(first);
        assertThatCode(() -> limiter.acquire(fifth)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(first)).isInstanceOf(ApiException.class);
    }
}
