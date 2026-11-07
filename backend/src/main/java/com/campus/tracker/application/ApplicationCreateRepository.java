package com.campus.tracker.application;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;
import static com.campus.tracker.application.ApplicationDtos.*;

/** Immutable receipts share the application transaction and remain scoped to the signed-in owner. */
@Repository
public class ApplicationCreateRepository {
    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    public ApplicationCreateRepository(JdbcTemplate jdbc, EntityManager entities) {
        this.jdbc = jdbc; this.entities = entities;
    }

    public record Receipt(String fingerprint, UUID applicationId) {}

    public Optional<Receipt> find(UUID owner, UUID key) {
        return jdbc.query("select payload_fingerprint,application_id from application_create_receipts where owner_id=? and idempotency_key=?",
                (row, index) -> new Receipt(row.getString(1), row.getObject(2, UUID.class)), owner, key).stream().findFirst();
    }

    public void insert(UUID owner, UUID key, String fingerprint, UUID applicationId) {
        jdbc.update("insert into application_create_receipts(owner_id,idempotency_key,payload_fingerprint,application_id) values (?,?,?,?)",
                owner, key, fingerprint, applicationId);
    }

    // One query includes the current stages and deletion state; normal resource GET intentionally hides deleted records.
    public Optional<ApplicationView> application(UUID owner, UUID id) {
        return entities.createQuery("select distinct a from InternshipApplication a left join fetch a.stages "
                        + "where a.id=:id and a.ownerId=:owner", InternshipApplication.class)
                .setParameter("id", id).setParameter("owner", owner).getResultList().stream().findFirst().map(ApplicationView::from);
    }
}
