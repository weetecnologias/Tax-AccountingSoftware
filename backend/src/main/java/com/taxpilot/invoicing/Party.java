package com.taxpilot.invoicing;

import java.util.UUID;

import com.taxpilot.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A customer or vendor of a client. */
@Entity
@Table(name = "parties")
public class Party extends BaseEntity {

    public enum Type { CUSTOMER, VENDOR, BOTH }

    @Column(name = "client_id", nullable = false, updatable = false) private UUID clientId;
    @Enumerated(EnumType.STRING) @Column(name = "party_type", nullable = false) private Type partyType;
    @Column(nullable = false) private String name;
    private String gstin;
    private String pan;
    @Column(name = "state_code") private String stateCode;
    private String email;
    private String phone;
    private String address;
    @Column(name = "credit_days", nullable = false) private short creditDays = 30;
    @Column(nullable = false) private boolean active = true;
    @Version private long version;

    protected Party() {
    }

    public Party(UUID clientId) {
        this.clientId = clientId;
    }

    public boolean isCustomer() { return partyType == Type.CUSTOMER || partyType == Type.BOTH; }
    public boolean isVendor() { return partyType == Type.VENDOR || partyType == Type.BOTH; }

    public UUID getClientId() { return clientId; }
    public Type getPartyType() { return partyType; }
    public String getName() { return name; }
    public String getGstin() { return gstin; }
    public String getPan() { return pan; }
    public String getStateCode() { return stateCode; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getAddress() { return address; }
    public short getCreditDays() { return creditDays; }
    public boolean isActive() { return active; }
    public long getVersion() { return version; }

    void update(Type type, String name, String gstin, String pan, String stateCode, String email, String phone,
                String address, short creditDays, boolean active) {
        this.partyType = type;
        this.name = name;
        this.gstin = gstin;
        this.pan = pan;
        this.stateCode = stateCode;
        this.email = email;
        this.phone = phone;
        this.address = address;
        this.creditDays = creditDays;
        this.active = active;
    }
}
