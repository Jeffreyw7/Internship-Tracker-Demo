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
    @org.springframework.data.jpa.repository.Query("select u.id from CampusUser u where u.email = :email")
    Optional<UUID> findIdByEmail(@org.springframework.data.repository.query.Param("email") String email);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from CampusUser u where u.id = :id")
    Optional<CampusUser> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    @org.springframework.data.jpa.repository.Query("select u.credentialVersion from CampusUser u where u.id = :id")
    Optional<Long> credentialVersion(@org.springframework.data.repository.query.Param("id") UUID id);
}
