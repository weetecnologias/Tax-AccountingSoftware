package com.taxpilot.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    Optional<UserSession> findByRefreshTokenHash(String hash);

    @Query("""
            select count(s) > 0 from UserSession s, com.taxpilot.users.User u
            where s.id = :sid and s.userId = :userId and u.id = s.userId
              and s.revokedAt is null and s.expiresAt > :now and u.active = true
            """)
    boolean isSessionActive(@Param("sid") UUID sid, @Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("update UserSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("""
            update UserSession s set s.revokedAt = :now
            where s.userId = :userId and s.revokedAt is null and s.id <> :keep
            """)
    int revokeOthersForUser(@Param("userId") UUID userId, @Param("keep") UUID keep, @Param("now") Instant now);
}
