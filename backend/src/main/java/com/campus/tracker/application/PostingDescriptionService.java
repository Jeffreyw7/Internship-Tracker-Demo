package com.campus.tracker.application;

import com.campus.tracker.common.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Called within ApplicationService's owner-scoped transaction. */
@Service
public class PostingDescriptionService {
    private final PostingDescriptionRepository repository;
    public PostingDescriptionService(PostingDescriptionRepository repository) {this.repository=repository;}
    public record Metadata(UUID id,int revision,String sourceUrl,Instant capturedAt,String captureMethod) {}
    public record Snapshot(UUID id,int revision,String sourceUrl,Instant capturedAt,String captureMethod,String descriptionText) {}
    public record Current(long applicationVersion,Snapshot snapshot) {}
    public record History(List<Metadata> content,long totalElements,int totalPages,int page,int size) {}
    boolean capture(InternshipApplication a,String text) {
        if(text==null || (a.descriptionRevision==0 && text.isEmpty())) return false;
        if(a.descriptionRevision>0 && snapshot(a.id,a.descriptionRevision).descriptionText.equals(text)) return false;
        a.descriptionRevision++;
        repository.save(new PostingDescriptionSnapshot(a,text));
        return true;
    }
    Current current(InternshipApplication a) {return new Current(a.version,a.descriptionRevision==0?null:view(snapshot(a.id,a.descriptionRevision)));}
    History history(UUID appId,int page,int size) {
        var result=repository.findByApplicationId(appId,PageRequest.of(page,size,Sort.by("revision").descending()));
        return new History(result.getContent(),result.getTotalElements(),result.getTotalPages(),page,size);
    }
    Snapshot get(UUID appId,UUID snapshotId) {return view(repository.findByIdAndApplicationId(snapshotId,appId).orElseThrow(PostingDescriptionService::missing));}
    private PostingDescriptionSnapshot snapshot(UUID appId,int revision) {return repository.findByApplicationIdAndRevision(appId,revision).orElseThrow(PostingDescriptionService::missing);}
    private static Snapshot view(PostingDescriptionSnapshot s) {return new Snapshot(s.id,s.revision,s.sourceUrl,s.capturedAt,s.captureMethod,s.descriptionText);}
    private static ApiException missing() {return new ApiException(HttpStatus.NOT_FOUND,"Saved description not found.");}
}
