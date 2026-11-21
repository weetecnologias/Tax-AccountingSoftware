package com.taxpilot.users;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("""
            select u from User u
            where (:q is null or lower(u.fullName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(u.email) like lower(concat('%', cast(:q as string), '%')))
              and (:active is null or u.active = :active)
            """)
    Page<User> search(@Param("q") String q, @Param("active") Boolean active, Pageable pageable);
}
