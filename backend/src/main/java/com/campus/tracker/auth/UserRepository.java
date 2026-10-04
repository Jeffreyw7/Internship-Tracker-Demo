// Lets Spring Data generate account persistence and email lookup queries.
package com.campus.tracker.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<CampusUser, UUID> {
    // Load the account for login, or return an empty result when the email is unknown.
    Optional<CampusUser> findByEmail(String email);
    // Check for an existing normalized email before attempting registration.
    boolean existsByEmail(String email);
}
