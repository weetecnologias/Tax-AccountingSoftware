package com.taxpilot.clients;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.ClientDtos.AddressDto;
import com.taxpilot.clients.ClientDtos.AddressRequest;
import com.taxpilot.clients.ClientDtos.BankAccountDto;
import com.taxpilot.clients.ClientDtos.BankAccountRequest;
import com.taxpilot.clients.ClientDtos.ContactDto;
import com.taxpilot.clients.ClientDtos.ContactRequest;
import com.taxpilot.clients.ClientDtos.GstRegistrationDto;
import com.taxpilot.clients.ClientDtos.GstRegistrationRequest;
import com.taxpilot.clients.ClientRepositories.AddressRepository;
import com.taxpilot.clients.ClientRepositories.BankAccountRepository;
import com.taxpilot.clients.ClientRepositories.ContactRepository;
import com.taxpilot.clients.ClientRepositories.GstRegistrationRepository;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.crypto.FieldEncryptor;
import com.taxpilot.common.validation.IndianIds;
import com.taxpilot.common.validation.IndianStates;
import com.taxpilot.security.CurrentUser;

/** Contacts, addresses, GST registrations and bank accounts belonging to a client. */
@Service
public class ClientRecordService {

    private final ClientService clients;
    private final ContactRepository contacts;
    private final AddressRepository addresses;
    private final GstRegistrationRepository gst;
    private final BankAccountRepository banks;
    private final FieldEncryptor encryptor;
    private final AuditService audit;

    public ClientRecordService(ClientService clients, ContactRepository contacts, AddressRepository addresses,
                               GstRegistrationRepository gst, BankAccountRepository banks, FieldEncryptor encryptor,
                               AuditService audit) {
        this.clients = clients;
        this.contacts = contacts;
        this.addresses = addresses;
        this.gst = gst;
        this.banks = banks;
        this.encryptor = encryptor;
        this.audit = audit;
    }

    // ---- contacts ----

    @Transactional(readOnly = true)
    public List<ContactDto> contacts(UUID clientId) {
        clients.find(clientId);
        return contacts.findByClientIdOrderByCreatedAtAsc(clientId).stream().map(ContactDto::of).toList();
    }

    @Transactional
    public ContactDto saveContact(UUID clientId, UUID contactId, ContactRequest req) {
        clients.findOpen(clientId);
        ClientRecords.Contact contact = contactId == null ? new ClientRecords.Contact(clientId)
                : owned(contacts.findById(contactId).orElse(null), clientId, "Contact");
        if (req.primary()) {
            contacts.findByClientIdOrderByCreatedAtAsc(clientId).forEach(c -> c.setPrimary(false));
        }
        contact.update(req.name().trim(), blank(req.designation()), blank(req.email()), blank(req.phone()),
                req.primary());
        contacts.save(contact);
        audit.record(contactId == null ? "CONTACT_ADDED" : "CONTACT_UPDATED", "CLIENT_CONTACT", contact.getId(),
                clientId, Map.of("name", contact.getName()));
        return ContactDto.of(contact);
    }

    @Transactional
    public void deleteContact(UUID clientId, UUID contactId) {
        clients.findOpen(clientId);
        ClientRecords.Contact contact = owned(contacts.findById(contactId).orElse(null), clientId, "Contact");
        contacts.delete(contact);
        audit.record("CONTACT_REMOVED", "CLIENT_CONTACT", contactId, clientId, Map.of("name", contact.getName()));
    }

    // ---- addresses ----

    @Transactional(readOnly = true)
    public List<AddressDto> addresses(UUID clientId) {
        clients.find(clientId);
        return addresses.findByClientIdOrderByCreatedAtAsc(clientId).stream().map(ClientRecordService::toDto).toList();
    }

    @Transactional
    public AddressDto saveAddress(UUID clientId, UUID addressId, AddressRequest req) {
        clients.findOpen(clientId);
        if (!IndianStates.isValid(req.stateCode())) {
            throw ApiException.field("stateCode", "Unknown state code");
        }
        if (!IndianIds.isValidPincode(req.pincode())) {
            throw ApiException.field("pincode", "PIN code must be 6 digits and cannot start with 0");
        }
        ClientRecords.Address address = addressId == null ? new ClientRecords.Address(clientId)
                : owned(addresses.findById(addressId).orElse(null), clientId, "Address");
        address.update(req.addressType(), req.line1().trim(), blank(req.line2()), req.city().trim(), req.stateCode(),
                req.pincode());
        addresses.save(address);
        audit.record(addressId == null ? "ADDRESS_ADDED" : "ADDRESS_UPDATED", "CLIENT_ADDRESS", address.getId(),
                clientId, Map.of("type", address.getAddressType(), "city", address.getCity()));
        return toDto(address);
    }

    @Transactional
    public void deleteAddress(UUID clientId, UUID addressId) {
        clients.findOpen(clientId);
        ClientRecords.Address address = owned(addresses.findById(addressId).orElse(null), clientId, "Address");
        addresses.delete(address);
        audit.record("ADDRESS_REMOVED", "CLIENT_ADDRESS", addressId, clientId, null);
    }

    // ---- GST registrations ----

    @Transactional(readOnly = true)
    public List<GstRegistrationDto> gstRegistrations(UUID clientId) {
        clients.find(clientId);
        return gst.findByClientIdOrderByCreatedAtAsc(clientId).stream().map(ClientRecordService::toDto).toList();
    }

    @Transactional
    public GstRegistrationDto saveGstRegistration(UUID clientId, UUID regId, GstRegistrationRequest req) {
        Client client = clients.findOpen(clientId);
        ClientRecords.GstRegistration reg;
        if (regId == null) {
            String gstin = IndianIds.normalise(req.gstin());
            if (!IndianIds.isValidGstin(gstin)) {
                throw ApiException.field("gstin", "GSTIN is invalid (format, state code or check digit)");
            }
            if (!IndianIds.panFromGstin(gstin).equals(clients.panOf(client))) {
                throw ApiException.field("gstin", "The PAN inside this GSTIN does not match the client's PAN");
            }
            if (gst.existsByGstin(gstin)) {
                throw ApiException.conflict("GSTIN_EXISTS", "This GSTIN is already registered to a client");
            }
            reg = new ClientRecords.GstRegistration(clientId, gstin, gstin.substring(0, 2));
        } else {
            reg = owned(gst.findById(regId).orElse(null), clientId, "GST registration");
            if (!reg.getGstin().equals(IndianIds.normalise(req.gstin()))) {
                throw ApiException.field("gstin", "A GSTIN cannot be changed; add a new registration instead");
            }
        }
        String status = req.status() == null || req.status().isBlank() ? "ACTIVE" : req.status();
        if ("CANCELLED".equals(status) && req.cancelledOn() == null) {
            throw ApiException.field("cancelledOn", "Enter the cancellation date");
        }
        if (req.cancelledOn() != null && req.cancelledOn().isBefore(req.effectiveDate())) {
            throw ApiException.field("cancelledOn", "Cancellation cannot be before the effective date");
        }
        reg.update(blank(req.tradeName()), req.registrationType(), req.effectiveDate(), req.filingFrequency(),
                blank(req.principalPlace()), status, "CANCELLED".equals(status) ? req.cancelledOn() : null);
        gst.save(reg);
        audit.record(regId == null ? "GST_REGISTRATION_ADDED" : "GST_REGISTRATION_UPDATED", "GST_REGISTRATION",
                reg.getId(), clientId, Map.of("gstin", reg.getGstin(), "status", reg.getStatus()));
        return toDto(reg);
    }

    // ---- bank accounts ----

    @Transactional(readOnly = true)
    public List<BankAccountDto> bankAccounts(UUID clientId) {
        clients.find(clientId);
        boolean reveal = canReveal();
        return banks.findByClientIdOrderByCreatedAtAsc(clientId).stream().map(b -> toDto(b, reveal)).toList();
    }

    @Transactional
    public BankAccountDto saveBankAccount(UUID clientId, UUID accountId, BankAccountRequest req) {
        clients.findOpen(clientId);
        String ifsc = IndianIds.normalise(req.ifsc());
        if (!IndianIds.isValidIfsc(ifsc)) {
            throw ApiException.field("ifsc", "IFSC must be 11 characters, e.g. HDFC0001234");
        }
        ClientRecords.BankAccount account;
        if (accountId == null) {
            account = new ClientRecords.BankAccount(clientId);
        } else {
            account = owned(banks.findById(accountId).orElse(null), clientId, "Bank account");
        }
        String number = req.accountNumber() == null ? "" : req.accountNumber().replace(" ", "");
        if (accountId == null || !number.isEmpty()) {
            if (!IndianIds.isValidAccountNumber(number)) {
                throw ApiException.field("accountNumber", "Account number must be 6 to 18 digits");
            }
            account.setAccountNumber(encryptor.encryptString(number), number.substring(number.length() - 4));
        }
        account.update(req.bankName().trim(), blank(req.branch()), req.accountHolder().trim(), ifsc,
                req.accountType(), req.active() == null || req.active());
        banks.save(account);
        audit.record(accountId == null ? "BANK_ACCOUNT_ADDED" : "BANK_ACCOUNT_UPDATED", "BANK_ACCOUNT",
                account.getId(), clientId, Map.of("bank", account.getBankName(),
                        "accountLast4", account.getAccountNumberLast4(), "active", account.isActive()));
        return toDto(account, canReveal());
    }

    // ---- mapping ----

    private static AddressDto toDto(ClientRecords.Address a) {
        return new AddressDto(a.getId(), a.getAddressType(), a.getLine1(), a.getLine2(), a.getCity(),
                a.getStateCode(), IndianStates.CODES.get(a.getStateCode()), a.getPincode());
    }

    private static GstRegistrationDto toDto(ClientRecords.GstRegistration g) {
        return new GstRegistrationDto(g.getId(), g.getGstin(), g.getTradeName(), g.getStateCode(),
                IndianStates.CODES.get(g.getStateCode()), g.getRegistrationType(), g.getEffectiveDate(),
                g.getFilingFrequency(), g.getPrincipalPlace(), g.getStatus(), g.getCancelledOn());
    }

    private BankAccountDto toDto(ClientRecords.BankAccount b, boolean reveal) {
        String number = reveal ? encryptor.decryptString(b.getAccountNumberEncrypted())
                : IndianIds.mask(b.getAccountNumberLast4(), 12);
        return new BankAccountDto(b.getId(), b.getBankName(), b.getBranch(), b.getAccountHolder(), number, reveal,
                b.getIfsc(), b.getAccountType(), b.isActive());
    }

    private static boolean canReveal() {
        CurrentUser me = CurrentUser.orNull();
        return me != null && me.has("CLIENT_PII_READ");
    }

    private static <T> T owned(T record, UUID clientId, String what) {
        UUID owner = switch (record) {
            case null -> null;
            case ClientRecords.Contact c -> c.getClientId();
            case ClientRecords.Address a -> a.getClientId();
            case ClientRecords.GstRegistration g -> g.getClientId();
            case ClientRecords.BankAccount b -> b.getClientId();
            default -> null;
        };
        if (!clientId.equals(owner)) {
            throw ApiException.notFound(what);
        }
        return record;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
