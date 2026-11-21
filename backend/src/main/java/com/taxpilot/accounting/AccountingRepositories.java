package com.taxpilot.accounting;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public final class AccountingRepositories {

    private AccountingRepositories() {
    }

    public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
        List<LedgerAccount> findByClientIdOrderByCodeAsc(UUID clientId);

        Optional<LedgerAccount> findByClientIdAndCode(UUID clientId, String code);

        boolean existsByClientId(UUID clientId);

        long countByParentId(UUID parentId);

        @Query("select count(l) > 0 from JournalLine l where l.accountId = :accountId")
        boolean isUsed(@Param("accountId") UUID accountId);
    }

    public interface PeriodRepository extends JpaRepository<AccountingPeriod, UUID> {
        List<AccountingPeriod> findByClientIdOrderByStartDateAsc(UUID clientId);

        List<AccountingPeriod> findByClientIdAndFinancialYearOrderByPeriodNumberAsc(UUID clientId, String fy);

        boolean existsByClientIdAndFinancialYear(UUID clientId, String fy);

        @Query("""
                select p from AccountingPeriod p
                where p.clientId = :clientId and :date between p.startDate and p.endDate
                """)
        Optional<AccountingPeriod> findContaining(@Param("clientId") UUID clientId, @Param("date") LocalDate date);

        @Query("""
                select count(p) > 0 from AccountingPeriod p
                where p.clientId = :clientId and p.startDate <= :end and p.endDate >= :start
                """)
        boolean overlaps(@Param("clientId") UUID clientId, @Param("start") LocalDate start, @Param("end") LocalDate end);
    }

    public interface JournalRepository extends JpaRepository<JournalEntry, UUID>, JpaSpecificationExecutor<JournalEntry> {
        Optional<JournalEntry> findByClientIdAndIdempotencyKey(UUID clientId, String key);

        long countByPeriodIdAndStatusIn(UUID periodId, Collection<JournalEntry.Status> statuses);

        long countByStatus(JournalEntry.Status status);

        long countByStatusAndPostedAtGreaterThanEqual(JournalEntry.Status status, java.time.Instant since);

        boolean existsByReversalOfIdAndStatusNot(UUID reversalOfId, JournalEntry.Status status);
    }
}
