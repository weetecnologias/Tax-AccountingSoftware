package com.taxpilot.clients;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositories for the clients module. */
public final class ClientRepositories {

    private ClientRepositories() {
    }

    public interface ClientRepository extends JpaRepository<Client, UUID>, JpaSpecificationExecutor<Client> {

        @Override
        @EntityGraph(attributePaths = {"accountant", "reviewer"})
        Page<Client> findAll(Specification<Client> spec, Pageable pageable);

        @Override
        @EntityGraph(attributePaths = {"accountant", "reviewer"})
        List<Client> findAll(Specification<Client> spec);

        Optional<Client> findByOrganisationIdAndPanHash(UUID organisationId, String panHash);

        @Query(value = "select nextval('client_code_seq')", nativeQuery = true)
        long nextClientCode();

        long countByStatus(ClientStatus status);

        @Query("select c.status, count(c) from Client c group by c.status")
        List<Object[]> countByStatusGrouped();

        @Query("select count(c) from Client c where c.status <> com.taxpilot.clients.ClientStatus.CLOSED "
                + "and c.accountant is null")
        long countOpenWithoutAccountant();
    }

    public interface StatusHistoryRepository extends JpaRepository<ClientStatusHistory, UUID> {
        List<ClientStatusHistory> findByClientIdOrderByChangedAtDesc(UUID clientId);

        Optional<ClientStatusHistory> findFirstByClientIdAndToStatusOrderByChangedAtDesc(UUID clientId,
                                                                                         ClientStatus toStatus);
    }

    public interface ContactRepository extends JpaRepository<ClientRecords.Contact, UUID> {
        List<ClientRecords.Contact> findByClientIdOrderByCreatedAtAsc(UUID clientId);

        long countByClientId(UUID clientId);
    }

    public interface AddressRepository extends JpaRepository<ClientRecords.Address, UUID> {
        List<ClientRecords.Address> findByClientIdOrderByCreatedAtAsc(UUID clientId);
    }

    public interface GstRegistrationRepository extends JpaRepository<ClientRecords.GstRegistration, UUID> {
        List<ClientRecords.GstRegistration> findByClientIdOrderByCreatedAtAsc(UUID clientId);

        boolean existsByGstin(String gstin);

        long countByClientId(UUID clientId);

        @Query("select count(g) from GstRegistration g where g.clientId = :clientId and g.status = 'ACTIVE'")
        long countActive(@Param("clientId") UUID clientId);
    }

    public interface BankAccountRepository extends JpaRepository<ClientRecords.BankAccount, UUID> {
        List<ClientRecords.BankAccount> findByClientIdOrderByCreatedAtAsc(UUID clientId);
    }
}
