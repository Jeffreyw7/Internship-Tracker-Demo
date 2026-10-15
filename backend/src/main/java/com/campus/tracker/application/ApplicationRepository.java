// Combines JPA persistence with specification-based searching and owner-scoped lookups.
package com.campus.tracker.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<InternshipApplication, UUID>, JpaSpecificationExecutor<InternshipApplication> {
    // Require record ID, signed-in owner, and deletion state in the same database lookup.
    Optional<InternshipApplication> findByIdAndOwnerIdAndDeleted(UUID id, UUID ownerId, boolean deleted);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000"),
            @QueryHint(name = "jakarta.persistence.query.timeout", value = "2000")})
    @Query("select a from InternshipApplication a where a.id=:id and a.ownerId=:ownerId and a.deleted=false")
    Optional<InternshipApplication> lockOwnedActive(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("select distinct a from InternshipApplication a left join fetch a.stages where a.id in :ids and a.ownerId=:ownerId and a.deleted=false")
    List<InternshipApplication> findImportTargets(@Param("ownerId") UUID ownerId, @Param("ids") Collection<UUID> ids);

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("select a from InternshipApplication a where a.ownerId=:ownerId and a.deleted=false and lower(trim(a.company))=:company and lower(trim(a.role))=:role order by a.id")
    List<InternshipApplication> findImportCandidates(@Param("ownerId") UUID ownerId, @Param("company") String company,
            @Param("role") String role, org.springframework.data.domain.Pageable page);
}
