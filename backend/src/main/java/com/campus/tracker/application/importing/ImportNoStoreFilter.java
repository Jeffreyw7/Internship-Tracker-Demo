package com.campus.tracker.application.importing;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Includes authentication, abuse and CSRF failures before import controllers run. */
public final class ImportNoStoreFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ImportUploadPolicy.isImport(request)) response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
