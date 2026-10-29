package com.campus.tracker.application.importing;

import com.campus.tracker.auth.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** These requests reach the production DispatcherServlet/controller; only session setup is a test servlet. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"debug=false","logging.level.root=WARN","logging.level.org.springframework=WARN","campus.imports.cleanup-enabled=false"})
@ActiveProfiles("test")
class ImportDecodedRouteServerTest {
    private static final String[] ALIASES={"/api/%61pplication-imports/preview","/api/application-imports/%70review"};
    private static final ConcurrentMap<String,Touches> TOUCHES=new ConcurrentHashMap<>();
    private static final class Touches {
        final AtomicInteger input=new AtomicInteger(),parameters=new AtomicInteger(),parts=new AtomicInteger(),reads=new AtomicInteger(),failures=new AtomicInteger();
        volatile long maximumReadNanos;
    }
    private record Session(String cookie,String token,UUID owner) {}
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext serverContext;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private URI uri(String path) { return URI.create("http://127.0.0.1:"+port+path); }
    private Session session() throws Exception {
        var setup=client.send(HttpRequest.newBuilder(uri("/test-decoded-import-session")).GET().build(),HttpResponse.BodyHandlers.ofString());
        String cookie=setup.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
        var csrf=client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
        return new Session(cookie,json.readTree(csrf.body()).get("token").asText(),UUID.fromString(setup.body()));
    }
    private String multipart() { return "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.csv\"\r\n\r\nCompany,Role\nA,B\r\n--b--\r\n"; }
    private HttpResponse<String> upload(String path,Session session,String testId,String token,String method) throws Exception {
        var request=HttpRequest.newBuilder(uri(path)).header("Cookie",session.cookie()).header("X-Import-Test-Id",testId)
                .header("Content-Type","multipart/form-data; boundary=b");
        if(token!=null) request.header("X-CSRF-TOKEN",token);
        return client.send(request.method(method,HttpRequest.BodyPublishers.ofString(multipart())).build(),HttpResponse.BodyHandlers.ofString());
    }
    private Touches tracked(String id) { return TOUCHES.computeIfAbsent(id,ignored->new Touches()); }
    private void untouched(String id) {
        var touches=tracked(id); assertThat(touches.input.get()).as("request input access").isZero();
        assertThat(touches.parameters.get()).as("request parameter access").isZero(); assertThat(touches.parts.get()).as("servlet multipart access").isZero();
    }
    @Test void aliasesWithMissingBlankInvalidCsrfNeverTouchBodyOrServletParts() throws Exception {
        for(String path:ALIASES) {
            var session=session();
            for(String token:new String[]{null," ","invalid"}) {
                String id=UUID.randomUUID().toString();
                var response=upload(path,session,id,token,"POST");
                assertThat(response.statusCode()).isEqualTo(403);
                untouched(id);
                assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            }
            for(String method:new String[]{"PUT","PATCH","DELETE"}) {
                String id=UUID.randomUUID().toString();
                assertThat(upload(path,session,id,null,method).statusCode()).isEqualTo(403); untouched(id);
            }
        }
    }
    @Test void aliasesReachRealControllerUnderPreviewRateAndBoundedReader() throws Exception {
        for(String path:ALIASES) {
            var session=session(); String id=UUID.randomUUID().toString();
            var response=upload(path,session,id,session.token(),"POST");
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(json.readTree(response.body()).get("rows").size()).isEqualTo(1);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(tracked(id).input.get()).isPositive(); assertThat(tracked(id).parts.get()).isZero();
            assertThat(jdbc.queryForObject("select request_count from application_import_rates where scope_key=?",Integer.class,"OWNER:"+session.owner()+":preview")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from application_import_rates where scope_key=?",Integer.class,"OWNER:"+session.owner()+":commit")).isZero();
        }
    }
    @Test void incompleteAliasBodiesRejectBeforeReadingAndChunkedNeverEntersAdmission() throws Exception {
        for(String path:ALIASES) {
            var session=session();
            for(String token:new String[]{null," ","invalid"}) {
                String id=UUID.randomUUID().toString();
                String response=raw(path,session,id,token,"Content-Length: 100000\r\n");
                assertThat(response).contains("403").containsIgnoringCase("Cache-Control: no-store"); untouched(id);
            }
            String id=UUID.randomUUID().toString();
            assertThat(raw(path,session,id,session.token(),"Transfer-Encoding: chunked\r\n")).contains("400"); untouched(id);
            assertThat(jdbc.queryForObject("select count(*) from application_import_rates where scope_key=?",Integer.class,"OWNER:"+session.owner()+":preview")).isZero();
        }
    }
    @Test void aliasStallAndConcurrentOwnerUseAdmissionAndErrorDispatchCannotParseParts() throws Exception {
        for(String path:ALIASES) {
            var session=session(); String id=UUID.randomUUID().toString();
            try(Socket socket=new Socket("127.0.0.1",port)) {
                socket.setSoTimeout(8000);
                socket.getOutputStream().write(headers(path,session,id,session.token(),"Content-Length: 100000\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write("--b\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                long until=System.nanoTime()+Duration.ofSeconds(3).toNanos();
                while(tracked(id).input.get()==0 && System.nanoTime()<until) Thread.sleep(10);
                assertThat(tracked(id).input.get()).isPositive();
                var rejected=upload(path,session,UUID.randomUUID().toString(),session.token(),"POST");
                assertThat(rejected.statusCode()).isEqualTo(429);
                assertThat(rejected.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
                assertThat(readHeaders(socket)).contains("408").containsIgnoringCase("Cache-Control: no-store");
            }
            assertThat(tracked(id).parts.get()).isZero();
            assertThat(upload(path,session,UUID.randomUUID().toString(),session.token(),"POST").statusCode()).isEqualTo(201);
        }
    }
    @Test void wrongImportEndpointMultipartNeverFallsBackToServletParts() throws Exception {
        var session=session(); String id=UUID.randomUUID().toString();
        var response=upload("/api/%61pplication-imports/"+UUID.randomUUID()+"/commit",session,id,session.token(),"POST");
        assertThat(response.statusCode()).isEqualTo(415); assertThat(tracked(id).parts.get()).isZero();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    @Test void admittedHeadersWithZeroBodyBytesReleaseCapacityWithinTheReadTimeout() throws Exception {
        var server=(org.springframework.boot.web.embedded.tomcat.TomcatWebServer)serverContext.getWebServer();
        var protocol=(org.apache.coyote.http11.AbstractHttp11Protocol<?>)server.getTomcat().getConnector().getProtocolHandler();
        assertThat(protocol.getConnectionUploadTimeout()).isEqualTo(5000);
        assertThat(protocol.getDisableUploadTimeout()).isFalse();
        var session=session(); String id=UUID.randomUUID().toString(); long started=System.nanoTime();
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(36000);
            socket.getOutputStream().write(headers(ALIASES[0],session,id,session.token(),"Content-Length: 100000\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            long until=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(tracked(id).input.get()==0 && System.nanoTime()<until) Thread.sleep(10);
            assertThat(tracked(id).input.get()).isPositive();
            Thread.sleep(6000);
            int capacityStatus=upload(ALIASES[0],session,UUID.randomUUID().toString(),session.token(),"POST").statusCode();
            String response=readHeaders(socket);
            assertThat(response).contains("408");
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(8));
            assertThat(capacityStatus).as("same-owner capacity at six seconds").isEqualTo(201);
            assertThat(tracked(id).reads.get()).isEqualTo(1);
            assertThat(tracked(id).failures.get()).isEqualTo(1);
            assertThat(Duration.ofNanos(tracked(id).maximumReadNanos)).isLessThan(Duration.ofSeconds(6));
        }
        assertThat(tracked(id).parts.get()).isZero();
    }
    private String headers(String path,Session session,String id,String token,String transport) {
        return "POST "+path+" HTTP/1.1\r\nHost: localhost\r\nCookie: "+session.cookie()+"\r\nX-Import-Test-Id: "+id
                +"\r\nContent-Type: multipart/form-data; boundary=b\r\n"+(token==null?"":"X-CSRF-TOKEN: "+token+"\r\n")+transport+"\r\n";
    }
    private String raw(String path,Session session,String id,String token,String transport) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(2500); socket.getOutputStream().write(headers(path,session,id,token,transport).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush(); return readHeaders(socket);
        }
    }
    private String readHeaders(Socket socket) throws IOException {
        var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)); var result=new StringBuilder();
        for(String line;(line=input.readLine())!=null && !line.isEmpty();) result.append(line).append('\n');
        return result.toString();
    }
    @TestConfiguration static class Instrumentation {
        @Bean ServletRegistrationBean<HttpServlet> decodedSession(UserRepository users) {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request,HttpServletResponse response) throws IOException {
                    var user=new CampusUser(UUID.randomUUID()+"@example.test","Decoded route test","unused"); user.verifyEmail(Instant.now()); users.saveAndFlush(user);
                    var principal=CampusPrincipal.from(user); var context=SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
                    request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
                    response.getWriter().write(user.getId().toString());
                }
            },"/test-decoded-import-session");
        }
        @Bean FilterRegistrationBean<Filter> bodyTouchRecorder() {
            var filter=new FilterRegistrationBean<Filter>((request,response,chain)->{
                var http=(HttpServletRequest)request; String id=http.getHeader("X-Import-Test-Id");
                if(id==null) { chain.doFilter(request,response); return; }
                var touches=TOUCHES.computeIfAbsent(id,ignored->new Touches());
                chain.doFilter(new HttpServletRequestWrapper(http) {
                    @Override public ServletInputStream getInputStream() throws IOException {
                        touches.input.incrementAndGet(); var source=super.getInputStream();
                        return new ServletInputStream() {
                            @Override public int read() throws IOException { byte[] one=new byte[1];return read(one,0,1)==-1?-1:one[0]&255; }
                            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                                touches.reads.incrementAndGet();long started=System.nanoTime();
                                try { return source.read(bytes,offset,length); }
                                catch(IOException e) { touches.failures.incrementAndGet();throw e; }
                                finally { touches.maximumReadNanos=Math.max(touches.maximumReadNanos,System.nanoTime()-started); }
                            }
                            @Override public boolean isReady() { return source.isReady(); }
                            @Override public boolean isFinished() { return source.isFinished(); }
                            @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                            @Override public void close() throws IOException { source.close(); }
                        };
                    }
                    @Override public BufferedReader getReader() throws IOException { touches.input.incrementAndGet(); return super.getReader(); }
                    @Override public String getParameter(String key) { touches.parameters.incrementAndGet(); return super.getParameter(key); }
                    @Override public Map<String,String[]> getParameterMap() { touches.parameters.incrementAndGet(); return super.getParameterMap(); }
                    @Override public Enumeration<String> getParameterNames() { touches.parameters.incrementAndGet(); return super.getParameterNames(); }
                    @Override public String[] getParameterValues(String key) { touches.parameters.incrementAndGet(); return super.getParameterValues(key); }
                    @Override public Collection<Part> getParts() throws IOException,ServletException { touches.parts.incrementAndGet(); return super.getParts(); }
                    @Override public Part getPart(String name) throws IOException,ServletException { touches.parts.incrementAndGet(); return super.getPart(name); }
                },response);
            });
            filter.setOrder(Integer.MIN_VALUE+20); filter.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.ERROR); return filter;
        }
    }
}
