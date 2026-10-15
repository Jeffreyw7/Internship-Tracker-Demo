package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ImportMultipartReaderTest {
    private final ImportMultipartReader reader = new ImportMultipartReader();
    private MockHttpServletRequest request(String parts) {
        var request = new MockHttpServletRequest("POST", "/api/application-imports/preview");
        request.setProtocol("HTTP/1.1");
        request.setContentType("multipart/form-data; boundary=test-boundary");
        request.setContent((parts + "--test-boundary--\r\n").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Content-Length", request.getContentLength());
        return request;
    }
    private String part(String name, String value) {
        return "--test-boundary\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }
    @Test void streamsCsvAndOptionalBlobOptionsWithoutServletMultipartResolution() {
        var upload = reader.read(request(part("file", "Company,Role\nA,B") + part("options", "{\"schemaVersion\":1}")));
        assertThat(new String(upload.file(), StandardCharsets.UTF_8)).isEqualTo("Company,Role\nA,B");
        assertThat(upload.optionsJson()).isEqualTo("{\"schemaVersion\":1}");
        assertThat(upload.toString()).doesNotContain("Company", "schemaVersion");
        assertThat(reader.read(request(part("file", "a\nb"))).optionsJson()).isNull();
    }
    @Test void rejectsDuplicateUnexpectedAndOversizedParts() {
        for (String parts : new String[]{part("file", "x") + part("file", "y"), part("secret-name", "private"),
                part("options", "{}"), part("file", "x".repeat(2097153)),
                part("file", "x") + part("options", "x".repeat(262145)),
                "--test-boundary\r\nContent-Disposition: form-data; name=\"file\"\r\nX-Large: " + "x".repeat(8192) + "\r\n\r\nx\r\n"}) {
            assertThatThrownBy(() -> reader.read(request(parts))).isInstanceOf(ApiException.class)
                    .hasMessageNotContaining("private");
        }
    }
    @Test void rejectsUnsupportedTransportBeforeReadingTheBody() {
        var chunked = request(part("file", "x"));
        chunked.addHeader("Transfer-Encoding", "chunked");
        assertThatThrownBy(() -> ImportUploadPolicy.checkTransport(chunked)).isInstanceOf(ApiException.class);
        var encoded = request(part("file", "x")); encoded.addHeader("Content-Encoding", "gzip");
        assertThatThrownBy(() -> ImportUploadPolicy.checkTransport(encoded)).isInstanceOf(ApiException.class);
        var http2 = request(part("file", "x")); http2.setProtocol("HTTP/2.0");
        assertThatThrownBy(() -> ImportUploadPolicy.checkTransport(http2)).isInstanceOf(ApiException.class);
    }
    @Test void excludesImportRouteFromStandardMultipartResolution() {
        var resolver = new ImportTransportConfiguration().multipartResolver();
        var preview = request(part("file", "a\nb"));
        assertThat(resolver.isMultipart(preview)).isFalse();
        var other = request(part("file", "a\nb")); other.setRequestURI("/other-upload");
        assertThat(resolver.isMultipart(other)).isTrue();
    }
    @Test void decodedImportFamilyContextAndErrorDispatchNeverUseServletMultipart() {
        var resolver = new ImportTransportConfiguration().multipartResolver();
        for (String path : new String[]{"/api/%61pplication-imports/preview", "/api/application-imports/%70review", "/api/application-imports/unknown", "/api/application-imports/a/commit"}) {
            var request = request(part("file", "a\nb")); request.setRequestURI(path);
            assertThat(ImportUploadPolicy.isImport(request)).as(path).isTrue();
            assertThat(resolver.isMultipart(request)).as(path).isFalse();
        }
        var contextual = request(part("file", "x")); contextual.setContextPath("/campus");
        contextual.setRequestURI("/campus/api/%61pplication-imports/%70review");
        assertThat(ImportUploadPolicy.isPreview(contextual)).isTrue();
        var error = request(part("file", "x")); error.setRequestURI("/error");
        error.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        error.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/api/%61pplication-imports/preview");
        assertThat(resolver.isMultipart(error)).isFalse();
    }
    @Test void decoderRetryNeverPerformsAnotherUnderlyingReadAfterTimeout() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var request = new MockHttpServletRequest("POST", "/api/application-imports/preview") {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                return new jakarta.servlet.ServletInputStream() {
                    @Override public int read() throws java.io.IOException { reads.incrementAndGet(); throw new java.net.SocketTimeoutException("synthetic timeout"); }
                    @Override public int read(byte[] bytes,int offset,int length) throws java.io.IOException { return read(); }
                    @Override public boolean isReady() { return true; }
                    @Override public boolean isFinished() { return false; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) {}
                };
            }
        };
        request.setProtocol("HTTP/1.1"); request.setContentType("multipart/form-data; boundary=b"); request.addHeader("Content-Length", 1000);
        assertThatThrownBy(() -> reader.read(request)).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.getCode()).isEqualTo("IMPORT_UPLOAD_TIMEOUT"));
        assertThat(reads.get()).isEqualTo(1);
    }
}
