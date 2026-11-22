package com.campus.tracker.application.importing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.campus.tracker.auth.CampusPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ImportIngressLogTest {
    private static final String ID = "ab123456-cdef-1234";
    private final UUID ownerId = UUID.randomUUID();
    private final Logger logger = (Logger) LoggerFactory.getLogger("campus.ingress.imports");
    private final Level previousLevel = logger.getLevel();
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final ImportWorkLimiter work = spy(new ImportWorkLimiter());
    private final ImportRequestFilter filter = new ImportRequestFilter(mock(ImportAdmissionService.class),
            work, new ObjectMapper(), new ImportInputLimits());

    @BeforeEach void capture() {
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.DEBUG);
        var owner = new CampusPrincipal(ownerId, "Test", "owner@example.test", "unused", true);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(owner, null, "ROLE_USER"));
    }

    @AfterEach void restore() {
        SecurityContextHolder.clearContext();
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
    }

    @ParameterizedTest
    @ValueSource(strings = {"commit", "preview"})
    void recordsSanitizedTimingAfterPermitRelease(String operation) throws Exception {
        var request = confirmation(ID, 2);
        if (operation.equals("preview")) {
            request.setRequestURI("/api/application-imports/preview");
            request.setContentType("multipart/form-data; boundary=fixture");
        }
        request.addHeader("CF-Connecting-IP", "192.0.2.1");
        request.addHeader("X-Forwarded-For", "192.0.2.3");
        request.addHeader("X-Forwarded-For", "192.0.2.2, 192.0.2.1");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("Cookie", "session=private-fixture");
        var response = new MockHttpServletResponse();
        doAnswer(call -> {
            assertThat(events.list).isEmpty();
            return call.callRealMethod();
        }).when(work).release(ownerId);
        filter.doFilter(request, response, (req, res) -> response.setStatus(204));
        verify(work).release(ownerId);
        assertThat(messages()).singleElement().satisfies(message -> {
            assertThat(message).contains("requestId=" + ID, "operation=" + operation, "servletStatus=204",
                    "http11=true forwardedPresent=false cfConnectingIpPresent=true xForwardedForPresent=true forwardedForTailMatchesCfIp=true protoHeaderIsHttps=true")
                    .doesNotContain("192.0.2.", "private-fixture", "owner@example.test");
            var times = Pattern.compile("filterReceivedAt=(\\S+) admittedAt=(\\S+) releasedAt=(\\S+)").matcher(message);
            assertThat(times.find()).isTrue();
            assertThat(Instant.parse(times.group(2))).isBetween(Instant.parse(times.group(1)), Instant.parse(times.group(3)));
        });
    }

    @Test void distinguishesRejectionBeforeAdmission() throws Exception {
        var request = confirmation(ID, 4097);
        request.addHeader("X-Forwarded-Proto", "https, http");
        request.addHeader("CF-Connecting-IP", "192.0.2.1");
        request.addHeader("X-Forwarded-For", "192.0.2.1, 192.0.2.2");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("unexpected admission"); });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(messages()).singleElement().asString().contains("requestId=" + ID,
                "admittedAt=null releasedAt=null servletStatus=413", "forwardedForTailMatchesCfIp=false", "protoHeaderIsHttps=false")
                .doesNotContain("192.0.2.");
    }

    @Test void omitsUnusableIdsDisabledLoggingAndAnonymousRequests() throws Exception {
        filter.doFilter(confirmation("private-fixture", 2), new MockHttpServletResponse(), (req, res) -> {});
        logger.setLevel(Level.OFF);
        filter.doFilter(confirmation(ID, 2), new MockHttpServletResponse(), (req, res) -> {});
        logger.setLevel(Level.DEBUG);
        SecurityContextHolder.clearContext();
        filter.doFilter(confirmation(ID, 2), new MockHttpServletResponse(), (req, res) -> {});
        assertThat(messages()).isEmpty();
    }

    private MockHttpServletRequest confirmation(String id, int length) {
        var request = new MockHttpServletRequest("POST", "/api/application-imports/" + UUID.randomUUID() + "/commit");
        request.setContentType("application/json");
        request.addHeader("Rndr-Id", id);
        request.addHeader("Content-Length", String.valueOf(length));
        return request;
    }

    private List<String> messages() {
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
