package com.campus.tracker.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<InternshipApplication, UUID>, JpaSpecificationExecutor<InternshipApplication> {
    Optional<InternshipApplication> findByIdAndOwnerIdAndDeleted(UUID id, UUID ownerId, boolean deleted);
}
