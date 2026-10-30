package com.campus.tracker.auth.abuse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class AuthEndpointsTest {
    private MockHttpServletRequest request(String method,String path,String context) {
        var request=new MockHttpServletRequest(method,context+path);
        request.setContextPath(context);
        request.setServletPath(path);
        return request;
    }

    @Test void loginAndRegistrationMatchOnlyPostAndTheirExactCaseSensitivePaths() {
        for(String context:new String[]{"","/campus"}) {
            for(String method:new String[]{"GET","HEAD","POST","PUT","DELETE","OPTIONS","PATCH"}) {
                assertThat(AuthEndpoints.LOGIN.matches(request(method,"/api/auth/login",context))).isEqualTo(method.equals("POST"));
                assertThat(AuthEndpoints.REGISTER.matches(request(method,"/api/auth/register",context))).isEqualTo(method.equals("POST"));
            }
            for(String path:new String[]{"/api/auth/login/","/api/auth/login/extra","/API/auth/login","/api/auth/login-other"})
                assertThat(AuthEndpoints.LOGIN.matches(request("POST",path,context))).isFalse();
            for(String path:new String[]{"/api/auth/register/","/api/auth/register/extra","/API/auth/register","/api/auth/register-other"})
                assertThat(AuthEndpoints.REGISTER.matches(request("POST",path,context))).isFalse();
        }
    }

    @Test void csrfMatcherKeepsAllMethodsWhileExcludingOtherPaths() {
        for(String context:new String[]{"","/campus"}) {
            for(String method:new String[]{"GET","HEAD","POST","PUT","DELETE","OPTIONS","PATCH"})
                assertThat(AuthEndpoints.CSRF.matches(request(method,"/api/auth/csrf",context))).isTrue();
            for(String path:new String[]{"/api/auth/csrf/","/api/auth/csrf/extra","/API/auth/csrf","/api/auth/csrf-other"})
                assertThat(AuthEndpoints.CSRF.matches(request("GET",path,context))).isFalse();
        }
    }

    @Test void queriesAndServletPathInfoSplitDoNotBypassAuthenticationMatchers() {
        var login=request("POST","/api/auth/login","/campus");
        login.setQueryString("next=/other");
        login.setServletPath("/api");login.setPathInfo("/auth/login");
        assertThat(AuthEndpoints.LOGIN.matches(login)).isTrue();
        var register=request("POST","/api/auth/register","/campus");
        register.setServletPath("/api");register.setPathInfo("/auth/register");
        assertThat(AuthEndpoints.REGISTER.matches(register)).isTrue();
        var csrf=request("HEAD","/api/auth/csrf","/campus");
        csrf.setServletPath("/api");csrf.setPathInfo("/auth/csrf");
        assertThat(AuthEndpoints.CSRF.matches(csrf)).isTrue();
    }
}
