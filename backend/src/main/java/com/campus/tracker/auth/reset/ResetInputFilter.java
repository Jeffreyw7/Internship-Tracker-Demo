package com.campus.tracker.auth.reset;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.Map;
import java.net.SocketTimeoutException;
import com.campus.tracker.auth.abuse.AuthThrottleException;

/** Bound decoded input before DTO binding, including chunked bodies and duplicate JSON fields. */
public final class ResetInputFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final ObjectMapper strict;
    private final RecoveryCapacity capacity;
    public ResetInputFilter(ObjectMapper json) {
        this(json,new RecoveryCapacity());
    }
    public ResetInputFilter(ObjectMapper json,RecoveryCapacity capacity) {
        this.json=json; this.capacity=capacity; this.strict=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        String path=request.getServletPath();
        if (path.isEmpty()) path=request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/auth/password-reset/")) { chain.doFilter(request,response); return; }
        response.setHeader("Cache-Control","no-store");
        if (!"POST".equals(request.getMethod())) { chain.doFilter(request,response); return; }
        try (var permit=capacity.ingress()) {
            bounded(request,response,chain);
        } catch (AuthThrottleException limited) {
            response.setHeader("Retry-After",Long.toString(limited.retryAfterSeconds()));
            error(response,429,AuthThrottleException.MESSAGE);
        } catch (SocketTimeoutException timeout) {
            response.setHeader("Connection","close");
            error(response,408,"The request body timed out.");
        }
    }
    private void bounded(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        String content=request.getContentType();
        if (content==null || !content.split(";",2)[0].trim().equalsIgnoreCase("application/json")
                || (request.getHeader("Content-Encoding")!=null && !"identity".equalsIgnoreCase(request.getHeader("Content-Encoding")))) {
            error(response,415,"Use an uncompressed JSON request."); return;
        }
        byte[] body;
        try { body=readBody(request.getInputStream()); }
        catch (IOException failure) {
            // Tomcat wraps a socket read timeout in ClientAbortException.
            for (Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if (cause instanceof SocketTimeoutException) throw new SocketTimeoutException();
            }
            throw failure;
        }
        if (body.length>4096) { response.setHeader("Connection","close"); error(response,413,"The request is too large."); return; }
        try {
            try (JsonParser parser=strict.createParser(body)) {
                if (strict.readTree(parser)==null || parser.nextToken()!=null) throw new IOException();
            }
        } catch (IOException invalid) { error(response,400,"Check the request format."); return; }
        HttpServletRequestWrapper bounded=new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return body.length; }
            @Override public long getContentLengthLong() { return body.length; }
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
        };
        chain.doFilter(bounded,response);
    }
    private byte[] readBody(ServletInputStream input) throws IOException {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        var bytes=new ByteArrayOutputStream();
        byte[] chunk=new byte[1024];
        while (bytes.size()<4097) {
            if (System.nanoTime()>deadline) throw new SocketTimeoutException();
            // The connector independently bounds each blocking read. Check the total deadline after it returns.
            int count=input.read(chunk,0,Math.min(chunk.length,4097-bytes.size()));
            if (System.nanoTime()>deadline) throw new SocketTimeoutException();
            if (count<0) break;
            bytes.write(chunk,0,count);
        }
        return bytes.toByteArray();
    }
    private void error(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        json.writeValue(response.getOutputStream(),Map.of("message",message));
    }
}
