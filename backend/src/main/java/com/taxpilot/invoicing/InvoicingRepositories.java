package com.taxpilot.invoicing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public final class InvoicingRepositories {

    private InvoicingRepositories() {
    }

    public interface PartyRepository extends JpaRepository<Party, UUID> {
        List<Party> findByClientIdOrderByNameAsc(UUID clientId);

        Optional<Party> findByClientIdAndGstin(UUID clientId, String gstin);
    }

    public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {
        Optional<Invoice> findByClientIdAndIdempotencyKey(UUID clientId, String key);

        @Query("""
                select count(i) > 0 from Invoice i
                where i.clientId = :clientId and i.invoiceType = com.taxpilot.invoicing.Invoice.Type.PURCHASE
                  and i.partyId = :partyId and lower(i.supplierInvoiceNumber) = lower(:number)
                  and i.status <> com.taxpilot.invoicing.Invoice.Status.CANCELLED and i.id <> :excludeId
                  and i.invoiceDate between :fyStart and :fyEnd
                """)
        boolean purchaseNumberUsed(@Param("clientId") UUID clientId, @Param("partyId") UUID partyId,
                                   @Param("number") String number, @Param("excludeId") UUID excludeId,
                                   @Param("fyStart") LocalDate fyStart, @Param("fyEnd") LocalDate fyEnd);

        long countByStatus(Invoice.Status status);
    }

    /** Rate slabs are read through JDBC in InvoiceService (statutory reference data, no JPA entity needed). */
}
