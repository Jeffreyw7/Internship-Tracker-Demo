package com.campus.tracker.application.importing;

import jakarta.servlet.http.HttpServletRequest;
import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.RequestDispatcher;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

public final class ImportUploadPolicy {
    private ImportUploadPolicy() {}
    public static final int FILE_BYTES = 2 * 1024 * 1024;
    public static final int OPTIONS_BYTES = 256 * 1024;
    public static final int REQUEST_BYTES = 3 * 1024 * 1024;
    static final String DEADLINE = ImportUploadPolicy.class.getName() + ".deadline";
    private static final String IMPORT_REQUEST = ImportUploadPolicy.class.getName() + ".importRequest";
    private static final PathPattern ROOT = PathPatternParser.defaultInstance.parse("/api/application-imports");
    private static final PathPattern FAMILY = PathPatternParser.defaultInstance.parse("/api/application-imports/{*remaining}");
    private static final PathPattern PREVIEW = PathPatternParser.defaultInstance.parse("/api/application-imports/preview");
    /** Match the same decoded path segments as MVC, never a raw-URI approximation. */
    public static boolean isImport(HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute(IMPORT_REQUEST))) return true;
        boolean matches = importPath(decodedPath(request));
        if (!matches && request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original)
            matches = importPath(RequestPath.parse(original, request.getContextPath()).pathWithinApplication());
        if (matches) request.setAttribute(IMPORT_REQUEST, Boolean.TRUE);
        return matches;
    }
    public static boolean isPreview(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && PREVIEW.matches(decodedPath(request));
    }
    private static boolean importPath(PathContainer path) { return ROOT.matches(path) || FAMILY.matches(path); }
    private static PathContainer decodedPath(HttpServletRequest request) {
        RequestPath path = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request) : ServletRequestPathUtils.parseAndCache(request);
        return path.pathWithinApplication();
    }
    public static void checkTransport(HttpServletRequest request) {
        checkTransport(request, new ImportInputLimits());
    }
    public static void checkTransport(HttpServletRequest request, ImportInputLimits limits) {
        if (!"HTTP/1.1".equals(request.getProtocol()) || request.getHeader("Transfer-Encoding") != null
                || (request.getHeader("Content-Encoding") != null && !"identity".equalsIgnoreCase(request.getHeader("Content-Encoding"))))
            throw error(HttpStatus.BAD_REQUEST, "IMPORT_UNSUPPORTED_TRANSPORT", "Use an unencoded fixed-length HTTP/1.1 upload.");
        String length = request.getHeader("Content-Length");
        if (length == null || !length.matches("[0-9]{1,10}")) throw error(HttpStatus.LENGTH_REQUIRED, "IMPORT_LENGTH_REQUIRED", "A valid Content-Length is required.");
        if (Long.parseLong(length) > limits.requestBytes()) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "IMPORT_REQUEST_LIMIT", "The upload exceeds the request limit.");
        String contentType = request.getContentType();
        if (contentType == null || contentType.length() > 256) throw malformed();
        try {
            var type = MediaType.parseMediaType(contentType);
            String boundary = type.getParameter("boundary");
            if (!"multipart".equalsIgnoreCase(type.getType()) || !"form-data".equalsIgnoreCase(type.getSubtype())
                    || boundary == null || boundary.isEmpty() || boundary.length() > 72) throw malformed();
        } catch (IllegalArgumentException e) { throw malformed(); }
    }
    public static void begin(HttpServletRequest request) { begin(request, new ImportInputLimits()); }
    public static void begin(HttpServletRequest request, ImportInputLimits limits) {
        request.setAttribute(DEADLINE, System.nanoTime() + limits.previewWorkMs() * 1_000_000L);
    }
    public static void checkDeadline(HttpServletRequest request) {
        Object deadline = request.getAttribute(DEADLINE);
        if (deadline instanceof Long time && System.nanoTime() - time >= 0) throw timeout();
    }
    public static void checkCurrentDeadline() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) checkDeadline(attributes.getRequest());
    }
    static ApiException timeout() { return error(HttpStatus.REQUEST_TIMEOUT, "IMPORT_UPLOAD_TIMEOUT", "The import upload deadline elapsed."); }
    static ApiException malformed() { return error(HttpStatus.BAD_REQUEST, "IMPORT_INVALID_MULTIPART", "The multipart upload is invalid."); }
    static ApiException error(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }
}
