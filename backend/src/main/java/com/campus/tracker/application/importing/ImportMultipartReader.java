package com.campus.tracker.application.importing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import com.campus.tracker.common.ApiException;
import java.io.*;
import java.net.SocketTimeoutException;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;
import org.apache.commons.fileupload2.core.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ImportMultipartReader {
    private final ImportInputLimits limits;
    public ImportMultipartReader() { this(new ImportInputLimits()); }
    @Autowired public ImportMultipartReader(ImportInputLimits limits) { this.limits = limits; }
    public record Upload(byte[] file, String optionsJson) {
        public Upload { file = file.clone(); }
        @Override public byte[] file() { return file.clone(); }
        @Override public String toString() { return "Upload[redacted]"; }
    }
    public Upload read(HttpServletRequest request) {
        ImportUploadPolicy.checkTransport(request, limits);
        if (request.getAttribute(ImportUploadPolicy.DEADLINE) == null) ImportUploadPolicy.begin(request, limits);
        try (var input = new BoundedInput(request, limits)) {
            var wrapped = new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() { return input; }
            };
            // Streaming getItemIterator never constructs FileItems or writes to a temporary directory.
            var decoder = new JakartaServletFileUpload<DiskFileItem, DiskFileItemFactory>();
            decoder.setMaxSize(limits.requestBytes());
            decoder.setMaxFileSize(Math.max(limits.fileBytes(), limits.optionsBytes()));
            decoder.setMaxFileCount(2);
            decoder.setMaxPartHeaderSize(8192);
            var parts = decoder.getItemIterator(wrapped);
            byte[] file = null;
            String options = null;
            int count = 0;
            while (parts.hasNext()) {
                ImportUploadPolicy.checkDeadline(request);
                if (++count > 2) throw ImportUploadPolicy.malformed();
                var part = parts.next();
                String field = part.getFieldName();
                if ("file".equals(field) && file == null) {
                    file = readPart(part.getInputStream(), limits.fileBytes(), request);
                } else if ("options".equals(field) && options == null) {
                    options = CsvImportParser.decodeUtf8(readPart(part.getInputStream(), limits.optionsBytes(), request));
                } else throw ImportUploadPolicy.malformed();
            }
            if (file == null) throw ImportUploadPolicy.malformed();
            // Consume bounded epilogue too; aggregate caps/deadline cover every advertised byte.
            var discard = new byte[8192];
            while (input.read(discard) != -1) { ImportUploadPolicy.checkDeadline(request); }
            return new Upload(file, options);
        } catch (ApiException e) { throw e; }
        catch (FileUploadSizeException e) { throw ImportUploadPolicy.error(HttpStatus.PAYLOAD_TOO_LARGE, "IMPORT_REQUEST_LIMIT", "The upload exceeds an import limit."); }
        catch (SocketTimeoutException e) { throw ImportUploadPolicy.timeout(); }
        catch (IOException | IllegalArgumentException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof SocketTimeoutException) throw ImportUploadPolicy.timeout();
            ImportUploadPolicy.checkDeadline(request);
            throw ImportUploadPolicy.malformed();
        }
    }
    private static byte[] readPart(InputStream input, int maximum, HttpServletRequest request) throws IOException {
        var output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        var buffer = new byte[8192];
        // Do not close part streams on rejection: FileUpload close may drain unread attacker data.
        for (int read; (read = input.read(buffer)) != -1;) {
            ImportUploadPolicy.checkDeadline(request);
            if (output.size() + read > maximum) throw ImportUploadPolicy.error(HttpStatus.PAYLOAD_TOO_LARGE,
                    "IMPORT_PART_LIMIT", "An upload part exceeds its import limit.");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
    private static final class BoundedInput extends ServletInputStream {
        private final HttpServletRequest request;
        private final ServletInputStream source;
        private final long advertised;
        private final int maximum;
        private long readBytes;
        private IOException failure;
        BoundedInput(HttpServletRequest request, ImportInputLimits limits) throws IOException {
            this.request = request; this.source = request.getInputStream();
            this.advertised = Long.parseLong(request.getHeader("Content-Length"));
            this.maximum = limits.requestBytes();
        }
        @Override public int read() throws IOException {
            var one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            // The decoder may retry while discovering a missing first boundary. A failed
            // transport read is terminal; never issue another socket read after it.
            if (failure != null) throw failure;
            ImportUploadPolicy.checkDeadline(request);
            if (length == 0) return 0;
            if (readBytes >= advertised) return -1;
            int count;
            try { count = source.read(buffer, offset, (int) Math.min(Math.min(length, 8192), advertised - readBytes)); }
            catch (IOException e) { failure = e; throw e; }
            ImportUploadPolicy.checkDeadline(request);
            if (count < 0) { failure = new EOFException("Incomplete import upload"); throw failure; }
            readBytes += count;
            if (readBytes > maximum) throw ImportUploadPolicy.error(HttpStatus.PAYLOAD_TOO_LARGE,
                    "IMPORT_REQUEST_LIMIT", "The upload exceeds the request limit.");
            return count;
        }
        @Override public boolean isFinished() { return readBytes >= advertised; }
        @Override public boolean isReady() { return source.isReady(); }
        @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Import uploads use blocking HTTP/1.1 reads"); }
        @Override public void close() throws IOException { source.close(); }
    }
}
