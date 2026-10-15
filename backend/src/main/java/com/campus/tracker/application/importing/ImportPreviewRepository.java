package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.campus.tracker.application.importing.ImportDtos.*;

/** JDBC TEXT storage avoids vendor-specific LOB/OID semantics. All metadata reads are owner scoped. */
@Repository
public class ImportPreviewRepository {
    record Stored(UUID id,UUID owner,String state,Instant created,Instant expires,Instant completed,Instant retain,
                  int schema,String policy,String sourceDigest,String reviewDigest,String payload,long payloadBytes,
                  String receipt,long receiptBytes,UUID successor) {
        @Override public String toString() { return "StoredImport[redacted]"; }
    }
    private final JdbcTemplate jdbc;
    public ImportPreviewRepository(JdbcTemplate jdbc) { this.jdbc=new ImportJdbcTemplate(jdbc.getDataSource()); }
    Stored find(UUID owner,UUID id,boolean lock) {
        var rows=jdbc.query("select * from application_imports where id=? and owner_id=?"+(lock?" for update nowait":""),
                (rs,n)->new Stored(rs.getObject("id",UUID.class),rs.getObject("owner_id",UUID.class),rs.getString("state"),
                        instant(rs,"created_at"),instant(rs,"expires_at"),instant(rs,"completed_at"),instant(rs,"receipt_retain_until"),
                        rs.getInt("schema_version"),rs.getString("policy_version"),rs.getString("source_digest"),rs.getString("review_digest"),
                        rs.getString("canonical_payload"),rs.getLong("payload_bytes"),rs.getString("receipt"),rs.getLong("receipt_bytes"),rs.getObject("successor_id",UUID.class)),id,owner);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"IMPORT_NOT_FOUND","Import not found.");
        return rows.get(0);
    }
    void insert(UUID owner,Preview preview,String sourceDigest,String payload) {
        jdbc.update("insert into application_imports(id,owner_id,state,created_at,expires_at,schema_version,policy_version,source_digest,review_digest,canonical_payload,payload_bytes) values (?,?,'PREVIEW',?,?,?,?,?,?,?,?)",
                preview.id(),owner,Timestamp.from(preview.createdAt()),Timestamp.from(preview.expiresAt()),preview.schemaVersion(),preview.policyVersion(),sourceDigest,preview.reviewDigest(),payload,ImportCanonicalizer.bytes(payload));
    }
    void supersede(Stored predecessor,UUID successor) {
        jdbc.update("update application_imports set state='SUPERSEDED',canonical_payload=null,payload_bytes=0,successor_id=? where id=? and owner_id=? and state='PREVIEW'",successor,predecessor.id(),predecessor.owner());
    }
    void complete(Stored stored,Receipt receipt,String serialized) {
        jdbc.update("update application_imports set state='COMPLETED',canonical_payload=null,payload_bytes=0,completed_at=?,receipt_retain_until=?,receipt=?,receipt_bytes=? where id=? and owner_id=? and state='PREVIEW'",
                Timestamp.from(receipt.completedAt()),Timestamp.from(receipt.retainUntil()),serialized,ImportCanonicalizer.bytes(serialized),stored.id(),stored.owner());
    }
    List<Stored> expiredCandidates(Instant now) {
        return jdbc.query("select id,owner_id from application_imports where (state in ('PREVIEW','SUPERSEDED') and expires_at<=?) or (state='COMPLETED' and receipt_retain_until<=?) order by created_at limit 100",
                (rs,n)->new Stored(rs.getObject("id",UUID.class),rs.getObject("owner_id",UUID.class),null,null,null,null,null,0,null,null,null,null,0,null,0,null),Timestamp.from(now),Timestamp.from(now));
    }
    void delete(Stored row) { jdbc.update("delete from application_imports where id=? and owner_id=?",row.id(),row.owner()); }
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant(); }
}
