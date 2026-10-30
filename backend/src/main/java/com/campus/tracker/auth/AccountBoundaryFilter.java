package com.campus.tracker.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

/** Reject a stale browser workspace before body parsing or mutation of a different signed-in account. */
public final class AccountBoundaryFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    public AccountBoundaryFilter(ObjectMapper json) { this.json=json; }

    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String expected=request.getHeader("X-Campus-Account");
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(expected!=null&&authentication!=null&&authentication.getPrincipal() instanceof CampusPrincipal principal
                &&!principal.id().toString().equals(expected)) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.setHeader("Cache-Control","no-store");
            json.writeValue(response.getOutputStream(),Map.of("code","ACCOUNT_CHANGED",
                    "message","The signed-in account changed. Reload your workspace."));
            return;
        }
        chain.doFilter(request,response);
    }
}
