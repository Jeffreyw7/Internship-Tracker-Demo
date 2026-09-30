// Lets Spring Data generate account persistence and email lookup queries.
package com.campus.tracker.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<CampusUser, UUID> {
    // Load the account for login, or return an empty result when the email is unknown.
    Optional<CampusUser> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from CampusUser u where u.id = :id")
    Optional<CampusUser> findByIdForUpdate(@Param("id") UUID id);
    // Check for an existing normalized email before attempting registration.
    boolean existsByEmail(String email);
    // Update only appearance; never overwrite identity or credentials from an old entity snapshot.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CampusUser u set u.themePreference = :preference where u.id = :id")
    int updateThemePreference(@Param("id") UUID id, @Param("preference") String preference);
}
