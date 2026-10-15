package com.campus.tracker.application.importing;

import com.campus.tracker.auth.CampusPrincipal;
import com.campus.tracker.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static com.campus.tracker.application.importing.ImportDtos.*;

@RestController
@RequestMapping("/api/application-imports")
public class ApplicationImportController {
    private final ImportMultipartReader reader;
    private final ImportPreviewService previews;
    private final ImportCommitService commits;
    private final ImportStatusService statuses;
    private final ImportCanonicalizer canonical;
    public ApplicationImportController(ImportMultipartReader reader,ImportPreviewService previews,ImportCommitService commits,ImportStatusService statuses,ImportCanonicalizer canonical) {
        this.reader=reader;this.previews=previews;this.commits=commits;this.statuses=statuses;this.canonical=canonical;
    }
    @PostMapping(value="/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Preview> preview(@AuthenticationPrincipal CampusPrincipal owner,HttpServletRequest request) {
        var upload=reader.read(request);
        Preview preview=previews.create(owner.id(),upload.file(),upload.optionsJson());
        return ResponseEntity.created(URI.create("/api/application-imports/"+preview.id())).cacheControl(CacheControl.noStore()).body(preview);
    }
    @PostMapping(value="/{id}/commit",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Receipt> commit(@AuthenticationPrincipal CampusPrincipal owner,@PathVariable UUID id,HttpServletRequest request) throws IOException {
        // Small strict body parsing also protects duplicate-key/depth semantics outside ordinary MVC binding.
        if(request.getContentLengthLong()>4096) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"IMPORT_SIZE_LIMIT","Import confirmation exceeds its size limit.");
        byte[] body=request.getInputStream().readNBytes(4097);
        if(body.length>4096) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"IMPORT_SIZE_LIMIT","Import confirmation exceeds its size limit.");
        var confirmation=canonical.read(body,CommitRequest.class);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(commits.commit(owner.id(),id,confirmation));
    }
    @GetMapping("/{id}")
    public ResponseEntity<Status> status(@AuthenticationPrincipal CampusPrincipal owner,@PathVariable UUID id) {
        Status status=statuses.status(owner.id(),id);
        var response=ResponseEntity.status(status.status().equals("UNRESOLVED")?202:200).cacheControl(CacheControl.noStore());
        if(status.status().equals("UNRESOLVED")) response.header(HttpHeaders.RETRY_AFTER,"2");
        return response.body(status);
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> busy(DataAccessException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).header(HttpHeaders.RETRY_AFTER,"2")
                .body(java.util.Map.of("code","IMPORT_PREVIEW_BUSY","message","The import is busy. Read status before retrying a write."));
    }
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> failure(ApiException error) {
        var response=ResponseEntity.status(error.getStatus()).cacheControl(CacheControl.noStore());
        if(error.getStatus()==HttpStatus.TOO_MANY_REQUESTS) response.header(HttpHeaders.RETRY_AFTER,"60");
        return response.body(java.util.Map.of("code",error.getCode()==null?"IMPORT_FAILED":error.getCode(),"message",error.getMessage()));
    }
}
