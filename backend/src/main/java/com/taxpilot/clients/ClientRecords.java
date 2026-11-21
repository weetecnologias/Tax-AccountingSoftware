package com.taxpilot.clients;

import java.time.LocalDate;
import java.util.UUID;

import com.taxpilot.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Client sub-records: contacts, addresses, GST registrations and bank accounts. */
public final class ClientRecords {

    private ClientRecords() {
    }

    @Entity(name = "ClientContact")
    @Table(name = "client_contacts")
    public static class Contact extends BaseEntity {
        @Column(name = "client_id", nullable = false, updatable = false)
        private UUID clientId;
        @Column(nullable = false)
        private String name;
        private String designation;
        private String email;
        private String phone;
        @Column(name = "is_primary", nullable = false)
        private boolean primary;

        protected Contact() {
        }

        public Contact(UUID clientId) {
            this.clientId = clientId;
        }

        public UUID getClientId() { return clientId; }
        public String getName() { return name; }
        public String getDesignation() { return designation; }
        public String getEmail() { return email; }
        public String getPhone() { return phone; }
        public boolean isPrimary() { return primary; }

        public void update(String name, String designation, String email, String phone, boolean primary) {
            this.name = name;
            this.designation = designation;
            this.email = email;
            this.phone = phone;
            this.primary = primary;
        }

        public void setPrimary(boolean primary) { this.primary = primary; }
    }

    @Entity(name = "ClientAddress")
    @Table(name = "client_addresses")
    public static class Address extends BaseEntity {
        @Column(name = "client_id", nullable = false, updatable = false)
        private UUID clientId;
        @Column(name = "address_type", nullable = false)
        private String addressType;
        @Column(nullable = false)
        private String line1;
        private String line2;
        @Column(nullable = false)
        private String city;
        @Column(name = "state_code", nullable = false)
        private String stateCode;
        @Column(nullable = false)
        private String pincode;

        protected Address() {
        }

        public Address(UUID clientId) {
            this.clientId = clientId;
        }

        public UUID getClientId() { return clientId; }
        public String getAddressType() { return addressType; }
        public String getLine1() { return line1; }
        public String getLine2() { return line2; }
        public String getCity() { return city; }
        public String getStateCode() { return stateCode; }
        public String getPincode() { return pincode; }

        public void update(String type, String line1, String line2, String city, String stateCode, String pincode) {
            this.addressType = type;
            this.line1 = line1;
            this.line2 = line2;
            this.city = city;
            this.stateCode = stateCode;
            this.pincode = pincode;
        }
    }

    @Entity(name = "GstRegistration")
    @Table(name = "gst_registrations")
    public static class GstRegistration extends BaseEntity {
        @Column(name = "client_id", nullable = false, updatable = false)
        private UUID clientId;
        @Column(nullable = false, updatable = false)
        private String gstin;
        @Column(name = "trade_name")
        private String tradeName;
        @Column(name = "state_code", nullable = false, updatable = false)
        private String stateCode;
        @Column(name = "registration_type", nullable = false)
        private String registrationType;
        @Column(name = "effective_date", nullable = false)
        private LocalDate effectiveDate;
        @Column(name = "filing_frequency", nullable = false)
        private String filingFrequency;
        @Column(name = "principal_place")
        private String principalPlace;
        @Column(nullable = false)
        private String status = "ACTIVE";
        @Column(name = "cancelled_on")
        private LocalDate cancelledOn;

        protected GstRegistration() {
        }

        public GstRegistration(UUID clientId, String gstin, String stateCode) {
            this.clientId = clientId;
            this.gstin = gstin;
            this.stateCode = stateCode;
        }

        public UUID getClientId() { return clientId; }
        public String getGstin() { return gstin; }
        public String getTradeName() { return tradeName; }
        public String getStateCode() { return stateCode; }
        public String getRegistrationType() { return registrationType; }
        public LocalDate getEffectiveDate() { return effectiveDate; }
        public String getFilingFrequency() { return filingFrequency; }
        public String getPrincipalPlace() { return principalPlace; }
        public String getStatus() { return status; }
        public LocalDate getCancelledOn() { return cancelledOn; }

        public void update(String tradeName, String registrationType, LocalDate effectiveDate,
                           String filingFrequency, String principalPlace, String status, LocalDate cancelledOn) {
            this.tradeName = tradeName;
            this.registrationType = registrationType;
            this.effectiveDate = effectiveDate;
            this.filingFrequency = filingFrequency;
            this.principalPlace = principalPlace;
            this.status = status;
            this.cancelledOn = cancelledOn;
        }
    }

    @Entity(name = "BankAccount")
    @Table(name = "bank_accounts")
    public static class BankAccount extends BaseEntity {
        @Column(name = "client_id", nullable = false, updatable = false)
        private UUID clientId;
        @Column(name = "bank_name", nullable = false)
        private String bankName;
        private String branch;
        @Column(name = "account_holder", nullable = false)
        private String accountHolder;
        @Column(name = "account_number_encrypted", nullable = false)
        private byte[] accountNumberEncrypted;
        @Column(name = "account_number_last4", nullable = false)
        private String accountNumberLast4;
        @Column(nullable = false)
        private String ifsc;
        @Column(name = "account_type", nullable = false)
        private String accountType;
        @Column(nullable = false)
        private boolean active = true;

        protected BankAccount() {
        }

        public BankAccount(UUID clientId) {
            this.clientId = clientId;
        }

        public UUID getClientId() { return clientId; }
        public String getBankName() { return bankName; }
        public String getBranch() { return branch; }
        public String getAccountHolder() { return accountHolder; }
        public byte[] getAccountNumberEncrypted() { return accountNumberEncrypted; }
        public String getAccountNumberLast4() { return accountNumberLast4; }
        public String getIfsc() { return ifsc; }
        public String getAccountType() { return accountType; }
        public boolean isActive() { return active; }

        public void update(String bankName, String branch, String accountHolder, String ifsc, String accountType,
                           boolean active) {
            this.bankName = bankName;
            this.branch = branch;
            this.accountHolder = accountHolder;
            this.ifsc = ifsc;
            this.accountType = accountType;
            this.active = active;
        }

        public void setAccountNumber(byte[] encrypted, String last4) {
            this.accountNumberEncrypted = encrypted;
            this.accountNumberLast4 = last4;
        }
    }
}
