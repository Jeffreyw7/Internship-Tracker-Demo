package com.campus.tracker.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<CampusUser, UUID> {
    Optional<CampusUser> findByEmail(String email);
    boolean existsByEmail(String email);
}
