// Combines JPA persistence with specification-based searching and owner-scoped lookups.
package com.campus.tracker.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<InternshipApplication, UUID>, JpaSpecificationExecutor<InternshipApplication> {
    // Require record ID, signed-in owner, and deletion state in the same database lookup.
    Optional<InternshipApplication> findByIdAndOwnerIdAndDeleted(UUID id, UUID ownerId, boolean deleted);
}
