package com.campus.tracker;

import com.campus.tracker.auth.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CredentialVersionFilterTest {
    @AfterEach void clearContext(){SecurityContextHolder.clearContext();}
    @Test void missingAccountCannotAuthorizeAnApiRequest() throws Exception {
        UserRepository users=mock(UserRepository.class);UUID id=authenticate();
        when(users.credentialVersion(id)).thenReturn(Optional.empty());
        var request=new MockHttpServletRequest("GET","/api/applications");
        var session=new MockHttpSession();request.setSession(session);
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();
        new CredentialVersionFilter(users).doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void unavailableVersionLookupFailsClosed() throws Exception {
        UserRepository users=mock(UserRepository.class);UUID id=authenticate();
        when(users.credentialVersion(id)).thenThrow(new DataAccessResourceFailureException("Synthetic outage"));
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();
        new CredentialVersionFilter(users).doFilter(new MockHttpServletRequest("GET","/api/applications"),response,chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(chain.getRequest()).isNull();
    }
    private UUID authenticate(){
        UUID id=UUID.randomUUID();var principal=new CampusPrincipal(id,"Test","test@example.test","unused",0);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        return id;
    }
}
