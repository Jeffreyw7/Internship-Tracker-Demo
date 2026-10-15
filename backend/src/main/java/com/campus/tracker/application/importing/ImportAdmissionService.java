package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Database locks serialize admission across instances; commit never takes these locks. */
@Service
public class ImportAdmissionService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ImportStorageProperties limits;
    @Value("${campus.imports.owner-preview-rate:10}") private int ownerPreviewRate;
    @Value("${campus.imports.global-preview-rate:60}") private int globalPreviewRate;
    @Value("${campus.imports.owner-commit-rate:10}") private int ownerCommitRate;
    @Value("${campus.imports.global-commit-rate:60}") private int globalCommitRate;
    @Value("${campus.imports.owner-status-rate:60}") private int ownerStatusRate;
    @Value("${campus.imports.global-status-rate:600}") private int globalStatusRate;
    public ImportAdmissionService(JdbcTemplate jdbc,Clock clock,ImportStorageProperties limits) { this.jdbc=new ImportJdbcTemplate(jdbc.getDataSource());this.clock=clock;this.limits=limits; }

    void lock(UUID owner) {
        globalLock(); String key="OWNER:"+owner;
        // The global lock prevents insert races, including first use by another application instance.
        if(jdbc.queryForObject("select count(*) from application_import_quota_locks where scope_key=?",Long.class,key)==0)
            jdbc.update("insert into application_import_quota_locks(scope_key) values (?)",key);
        jdbc.queryForObject("select scope_key from application_import_quota_locks where scope_key=? for update nowait",String.class,key);
    }
    void globalLock() { jdbc.queryForObject("select scope_key from application_import_quota_locks where scope_key='GLOBAL' for update nowait",String.class); }

    @Transactional(timeout=5)
    public void checkRate(UUID owner,String operation) {
        if(!Set.of("preview","commit","status").contains(operation)) throw new IllegalArgumentException("Invalid import operation.");
        globalLock();
        Instant now=clock.instant();
        int ownerLimit=switch(operation) { case "status"->ownerStatusRate;case "commit"->ownerCommitRate;default->ownerPreviewRate; };
        int globalLimit=switch(operation) { case "status"->globalStatusRate;case "commit"->globalCommitRate;default->globalPreviewRate; };
        consume("GLOBAL:"+operation,globalLimit,now);
        consume("OWNER:"+owner+":"+operation,ownerLimit,now);
    }
    private void consume(String key,int capacity,Instant now) {
        var existing=jdbc.query("select window_started,request_count from application_import_rates where scope_key=?",(rs,n)->Map.entry(rs.getTimestamp(1).toInstant(),rs.getInt(2)),key);
        if(existing.isEmpty()) { jdbc.update("insert into application_import_rates values (?,?,1)",key,Timestamp.from(now)); return; }
        var bucket=existing.get(0);
        if(!now.isBefore(bucket.getKey().plusSeconds(60))) { jdbc.update("update application_import_rates set window_started=?,request_count=1 where scope_key=?",Timestamp.from(now),key); return; }
        if(bucket.getValue()>=capacity) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"IMPORT_RATE_LIMIT","Import request rate exceeded. Try again later.");
        jdbc.update("update application_import_rates set request_count=request_count+1 where scope_key=?",key);
    }

    void requireCapacity(UUID owner,long payloadBytes,ImportPreviewRepository.Stored predecessor) {
        // One aggregate statement: a concurrent commit cannot disappear between reservation and receipt snapshots.
        long[] totals=jdbc.queryForObject("select "
                +"coalesce(sum(case when state='PREVIEW' then 1 else 0 end),0),"
                +"coalesce(sum(payload_bytes),0),"
                +"coalesce(sum(case when state in ('PREVIEW','COMPLETED') then 1 else 0 end),0),"
                +"coalesce(sum(case when state='PREVIEW' then 131072 else receipt_bytes end),0),"
                +"coalesce(sum(case when state<>'COMPLETED' then 1 else 0 end),0),"
                +"coalesce(sum(case when owner_id=? and state='PREVIEW' then 1 else 0 end),0),"
                +"coalesce(sum(case when owner_id=? then payload_bytes else 0 end),0),"
                +"coalesce(sum(case when owner_id=? and state in ('PREVIEW','COMPLETED') then 1 else 0 end),0),"
                +"coalesce(sum(case when owner_id=? then case when state='PREVIEW' then 131072 else receipt_bytes end else 0 end),0),"
                +"coalesce(sum(case when owner_id=? and state<>'COMPLETED' then 1 else 0 end),0) from application_imports",
                (rs,n)-> { long[] result=new long[10];for(int i=0;i<10;i++) result[i]=rs.getLong(i+1);return result; },owner,owner,owner,owner,owner);
        if(predecessor!=null) for(int base:new int[]{0,5}) { totals[base]--;totals[base+1]-=predecessor.payloadBytes();totals[base+2]--;totals[base+3]-=ImportCanonicalizer.RECEIPT_LIMIT; }
        if(totals[0]+1>limits.globalPreviewCount() || totals[1]+payloadBytes>limits.globalPreviewBytes() || totals[2]+1>limits.globalReceiptCount() || totals[3]+ImportCanonicalizer.RECEIPT_LIMIT>limits.globalReceiptBytes() || totals[4]+1>limits.globalMetadataCount()
                || totals[5]+1>limits.ownerPreviewCount() || totals[6]+payloadBytes>limits.ownerPreviewBytes() || totals[7]+1>limits.ownerReceiptCount() || totals[8]+ImportCanonicalizer.RECEIPT_LIMIT>limits.ownerReceiptBytes() || totals[9]+1>limits.ownerMetadataCount())
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"IMPORT_STORAGE_LIMIT","Import storage quota reached. Wait for older previews to expire.");
    }
    void retireUnusedOwnerLocks() {
        jdbc.update("delete from application_import_quota_locks where scope_key<>'GLOBAL' and scope_key not in (select concat('OWNER:',cast(owner_id as varchar(36))) from application_imports)");
        jdbc.update("delete from application_import_rates where window_started<?",Timestamp.from(clock.instant().minusSeconds(120)));
    }
}
