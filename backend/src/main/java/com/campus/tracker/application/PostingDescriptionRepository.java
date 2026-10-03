package com.campus.tracker.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

interface PostingDescriptionRepository extends JpaRepository<PostingDescriptionSnapshot,UUID> {
    Optional<PostingDescriptionSnapshot> findByApplicationIdAndRevision(UUID applicationId,int revision);
    Optional<PostingDescriptionSnapshot> findByIdAndApplicationId(UUID id,UUID applicationId);
    // Projection keeps large description text out of paginated history reads.
    Page<PostingDescriptionService.Metadata> findByApplicationId(UUID applicationId,Pageable pageable);
}
