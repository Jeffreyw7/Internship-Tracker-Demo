package com.campus.tracker.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bound JSON before deserialization, including requests using chunked transfer encoding. */
public class PasswordRequestSizeFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        String path=req.getRequestURI().substring(req.getContextPath().length());
        if(!req.getMethod().equals("POST")||!java.util.Set.of("/api/auth/password-reset/request","/api/auth/password-reset/confirm",
                "/api/auth/password-change/request","/api/auth/password-change/confirm").contains(path)){chain.doFilter(req,res);return;}
        byte[] body=req.getInputStream().readNBytes(4097);
        if(body.length>4096){res.setStatus(413);res.setContentType("application/json");res.setHeader("Cache-Control","no-store");res.getWriter().write("{\"message\":\"Request is too large.\"}");return;}
        chain.doFilter(new HttpServletRequestWrapper(req){
            @Override public ServletInputStream getInputStream(){
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream(){
                    @Override public int read(){return input.read();}
                    @Override public boolean isFinished(){return input.available()==0;}
                    @Override public boolean isReady(){return true;}
                    @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },res);
    }
}
