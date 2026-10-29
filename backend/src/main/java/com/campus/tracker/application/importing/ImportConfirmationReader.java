package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.SocketTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Check the absolute deadline between underlying reads; readNBytes hides slow partial reads. */
@Component
public class ImportConfirmationReader {
    public byte[] read(HttpServletRequest request) {
        ImportUploadPolicy.checkConfirmationTransport(request);
        try (var source = request.getInputStream()) {
            int remaining = Math.toIntExact(request.getContentLengthLong());
            var output = new ByteArrayOutputStream(remaining);
            var buffer = new byte[1024];
            while (remaining > 0) {
                ImportUploadPolicy.checkDeadline(request);
                int count = source.read(buffer, 0, Math.min(buffer.length, remaining));
                ImportUploadPolicy.checkDeadline(request);
                if (count < 0) throw new EOFException("Incomplete import confirmation");
                output.write(buffer, 0, count); remaining -= count;
            }
            ImportUploadPolicy.checkDeadline(request);
            return output.toByteArray();
        } catch (ApiException e) { throw e; }
        catch (IOException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof SocketTimeoutException) throw ImportUploadPolicy.timeout();
            ImportUploadPolicy.checkDeadline(request);
            throw ImportUploadPolicy.error(HttpStatus.BAD_REQUEST, "IMPORT_INVALID_CONTENT", "Import confirmation body is incomplete.");
        }
    }
}
