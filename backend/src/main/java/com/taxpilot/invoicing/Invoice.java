package com.taxpilot.invoicing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.taxpilot.accounting.Money;
import com.taxpilot.common.BaseEntity;
import com.taxpilot.common.Organisations;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "invoices")
public class Invoice extends BaseEntity {

    public enum Type { SALES, PURCHASE }

    public enum Status { DRAFT, PENDING_APPROVAL, POSTED, CANCELLED }

    public enum SupplyType { INTRA, INTER }

    @Column(name = "organisation_id", nullable = false, updatable = false) private UUID organisationId = Organisations.DEFAULT_ID;
    @Column(name = "client_id", nullable = false, updatable = false) private UUID clientId;
    @Enumerated(EnumType.STRING) @Column(name = "invoice_type", nullable = false, updatable = false) private Type invoiceType;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.DRAFT;
    @Column(name = "invoice_number") private String invoiceNumber;
    @Column(name = "supplier_invoice_number") private String supplierInvoiceNumber;
    @Column(name = "invoice_date", nullable = false) private LocalDate invoiceDate;
    @Column(name = "due_date") private LocalDate dueDate;
    @Column(name = "party_id", nullable = false) private UUID partyId;
    @Column(name = "gst_registration_id") private UUID gstRegistrationId;
    @Column(name = "place_of_supply", nullable = false) private String placeOfSupply;
    @Enumerated(EnumType.STRING) @Column(name = "supply_type", nullable = false) private SupplyType supplyType;
    @Column(name = "reverse_charge", nullable = false) private boolean reverseCharge;
    @Column(name = "taxable_total", nullable = false) private BigDecimal taxableTotal = Money.ZERO;
    @Column(name = "cgst_total", nullable = false) private BigDecimal cgstTotal = Money.ZERO;
    @Column(name = "sgst_total", nullable = false) private BigDecimal sgstTotal = Money.ZERO;
    @Column(name = "igst_total", nullable = false) private BigDecimal igstTotal = Money.ZERO;
    @Column(name = "grand_total", nullable = false) private BigDecimal grandTotal = Money.ZERO;
    private String notes;
    @Column(name = "journal_entry_id") private UUID journalEntryId;
    @Column(name = "prepared_by", nullable = false, updatable = false) private UUID preparedBy;
    @Column(name = "submitted_by") private UUID submittedBy;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "approved_by") private UUID approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "self_approved", nullable = false) private boolean selfApproved;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "idempotency_key", updatable = false) private String idempotencyKey;
    @Version private long version;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<Line> lines = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(UUID clientId, Type type, UUID preparedBy, String idempotencyKey) {
        this.clientId = clientId;
        this.invoiceType = type;
        this.preparedBy = preparedBy;
        this.idempotencyKey = idempotencyKey;
    }

    void setHeader(LocalDate invoiceDate, LocalDate dueDate, UUID partyId, UUID gstRegistrationId, String pos,
                   SupplyType supplyType, boolean reverseCharge, String supplierInvoiceNumber, String notes) {
        this.invoiceDate = invoiceDate;
        this.dueDate = dueDate;
        this.partyId = partyId;
        this.gstRegistrationId = gstRegistrationId;
        this.placeOfSupply = pos;
        this.supplyType = supplyType;
        this.reverseCharge = reverseCharge;
        this.supplierInvoiceNumber = supplierInvoiceNumber;
        this.notes = notes;
    }

    void replaceLines(List<Line> newLines, InvoiceCalculator.Totals totals) {
        lines.clear();
        short n = 1;
        for (Line l : newLines) {
            l.invoice = this;
            l.lineNo = n++;
            lines.add(l);
        }
        taxableTotal = totals.taxable();
        cgstTotal = totals.cgst();
        sgstTotal = totals.sgst();
        igstTotal = totals.igst();
        grandTotal = totals.grand();
    }

    void submit(UUID by) {
        status = Status.PENDING_APPROVAL;
        submittedBy = by;
        submittedAt = Instant.now();
        rejectionReason = null;
    }

    void reject(String reason) {
        status = Status.DRAFT;
        rejectionReason = reason;
        submittedBy = null;
        submittedAt = null;
    }

    void post(UUID approver, boolean self, String number, UUID journalId) {
        status = Status.POSTED;
        approvedBy = approver;
        approvedAt = Instant.now();
        selfApproved = self;
        if (number != null) invoiceNumber = number;
        journalEntryId = journalId;
    }

    void cancel() {
        status = Status.CANCELLED;
    }

    public UUID getClientId() { return clientId; }
    public Type getInvoiceType() { return invoiceType; }
    public Status getStatus() { return status; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public String getSupplierInvoiceNumber() { return supplierInvoiceNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public LocalDate getDueDate() { return dueDate; }
    public UUID getPartyId() { return partyId; }
    public UUID getGstRegistrationId() { return gstRegistrationId; }
    public String getPlaceOfSupply() { return placeOfSupply; }
    public SupplyType getSupplyType() { return supplyType; }
    public boolean isReverseCharge() { return reverseCharge; }
    public BigDecimal getTaxableTotal() { return taxableTotal; }
    public BigDecimal getCgstTotal() { return cgstTotal; }
    public BigDecimal getSgstTotal() { return sgstTotal; }
    public BigDecimal getIgstTotal() { return igstTotal; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public String getNotes() { return notes; }
    public UUID getJournalEntryId() { return journalEntryId; }
    public UUID getPreparedBy() { return preparedBy; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public boolean isSelfApproved() { return selfApproved; }
    public String getRejectionReason() { return rejectionReason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getVersion() { return version; }
    public List<Line> getLines() { return lines; }

    @Entity(name = "InvoiceLine")
    @Table(name = "invoice_lines")
    public static class Line {
        @Id private UUID id = UUID.randomUUID();
        @ManyToOne(optional = false) @JoinColumn(name = "invoice_id", nullable = false, updatable = false) private Invoice invoice;
        @Column(name = "line_no", nullable = false) private short lineNo;
        @Column(nullable = false) private String description;
        @Column(name = "hsn_sac") private String hsnSac;
        @Column(name = "account_id", nullable = false) private UUID accountId;
        @Column(nullable = false) private BigDecimal quantity;
        @Column(name = "unit_price", nullable = false) private BigDecimal unitPrice;
        @Column(name = "taxable_value", nullable = false) private BigDecimal taxableValue;
        @Column(name = "gst_rate", nullable = false) private BigDecimal gstRate;
        @Column(nullable = false) private BigDecimal cgst = Money.ZERO;
        @Column(nullable = false) private BigDecimal sgst = Money.ZERO;
        @Column(nullable = false) private BigDecimal igst = Money.ZERO;

        protected Line() {
        }

        Line(String description, String hsnSac, UUID accountId, InvoiceCalculator.LineResult r) {
            this.description = description;
            this.hsnSac = hsnSac;
            this.accountId = accountId;
            this.quantity = r.quantity();
            this.unitPrice = r.unitPrice();
            this.taxableValue = r.taxable();
            this.gstRate = r.rate();
            this.cgst = r.cgst();
            this.sgst = r.sgst();
            this.igst = r.igst();
        }

        public UUID getId() { return id; }
        public short getLineNo() { return lineNo; }
        public String getDescription() { return description; }
        public String getHsnSac() { return hsnSac; }
        public UUID getAccountId() { return accountId; }
        public BigDecimal getQuantity() { return quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public BigDecimal getTaxableValue() { return taxableValue; }
        public BigDecimal getGstRate() { return gstRate; }
        public BigDecimal getCgst() { return cgst; }
        public BigDecimal getSgst() { return sgst; }
        public BigDecimal getIgst() { return igst; }
    }
}
