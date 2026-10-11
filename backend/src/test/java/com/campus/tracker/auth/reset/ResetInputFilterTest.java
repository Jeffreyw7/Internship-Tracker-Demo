package com.campus.tracker.auth.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.net.SocketTimeoutException;
import static org.assertj.core.api.Assertions.*;

class ResetInputFilterTest {
    @Test void saturatedAdmissionRejectsBeforeReadingBody() throws Exception {
        var capacity=new RecoveryCapacity();
        var request=new MockHttpServletRequest("POST","/api/auth/password-reset/request") {
            @Override public ServletInputStream getInputStream() { throw new AssertionError("Must not read before admission"); }
        };
        request.setContentType("application/json");
        var response=new MockHttpServletResponse();
        try (var a=capacity.ingress();var b=capacity.ingress();var c=capacity.ingress();var d=capacity.ingress()) {
            new ResetInputFilter(new ObjectMapper(),capacity).doFilter(request,response,(req,res)->fail("Must reject saturation"));
        }
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }
    @Test void socketReadTimeoutProducesBoundedClientFailure() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/auth/password-reset/request") {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    public int read() throws java.io.IOException { throw new SocketTimeoutException(); }
                    public boolean isFinished() { return false; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener listener) { }
                };
            }
        };
        request.setContentType("application/json");
        var response=new MockHttpServletResponse();
        new ResetInputFilter(new ObjectMapper()).doFilter(request,response,(req,res)->fail("Body must not reach controller"));
        assertThat(response.getStatus()).isEqualTo(408);
        assertThat(response.getHeader("Connection")).isEqualTo("close");
    }
}
