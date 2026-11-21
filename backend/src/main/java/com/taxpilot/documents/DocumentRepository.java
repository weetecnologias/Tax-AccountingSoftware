package com.taxpilot.documents;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

    Optional<Document> findFirstByClientIdAndSha256OrderByUploadedAtAsc(UUID clientId, String sha256);

    long countByClientId(UUID clientId);

    long countByClientIdAndStatusIn(UUID clientId, Collection<Document.Status> statuses);

    long countByStatusIn(Collection<Document.Status> statuses);

    long countByUploadedAtGreaterThanEqual(Instant since);

    @Query("select d.status, count(d) from Document d group by d.status")
    List<Object[]> countByStatusGrouped();

    @Query(value = """
            select cast(date_trunc('day', uploaded_at at time zone 'Asia/Kolkata') as date) as day, count(*)
            from documents where uploaded_at >= :since group by 1 order by 1
            """, nativeQuery = true)
    List<Object[]> uploadsPerDay(@Param("since") Instant since);
}
