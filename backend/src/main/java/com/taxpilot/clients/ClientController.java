package com.taxpilot.clients;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.clients.ClientDtos.AddressDto;
import com.taxpilot.clients.ClientDtos.AddressRequest;
import com.taxpilot.clients.ClientDtos.BankAccountDto;
import com.taxpilot.clients.ClientDtos.BankAccountRequest;
import com.taxpilot.clients.ClientDtos.ClientDetail;
import com.taxpilot.clients.ClientDtos.ClientRequest;
import com.taxpilot.clients.ClientDtos.ClientSummary;
import com.taxpilot.clients.ClientDtos.ContactDto;
import com.taxpilot.clients.ClientDtos.ContactRequest;
import com.taxpilot.clients.ClientDtos.GstRegistrationDto;
import com.taxpilot.clients.ClientDtos.GstRegistrationRequest;
import com.taxpilot.clients.ClientDtos.StatusChangeRequest;
import com.taxpilot.clients.ClientDtos.StatusHistoryEntry;
import com.taxpilot.common.PageResponse;
import com.taxpilot.common.Paging;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/clients")
public class ClientController {

    private final ClientService clients;
    private final ClientRecordService records;

    public ClientController(ClientService clients, ClientRecordService records) {
        this.clients = clients;
        this.records = records;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CLIENT_READ')")
    public PageResponse<ClientSummary> list(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) ClientStatus status,
                                            @RequestParam(required = false) EntityType entityType,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String sort) {
        return clients.list(q, status, entityType, Paging.of(page, size, sort,
                Set.of("displayName", "clientCode", "createdAt", "status"), "createdAt"));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('CLIENT_EXPORT')")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) ClientStatus status,
                                         @RequestParam(required = false) EntityType entityType) {
        byte[] body = ("﻿" + clients.exportCsv(q, status, entityType)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("clients-" + LocalDate.now() + ".csv").build().toString())
                .body(body);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public ClientDetail create(@Valid @RequestBody ClientRequest request) {
        return clients.create(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CLIENT_READ')")
    public ClientDetail get(@PathVariable UUID id) {
        return clients.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public ClientDetail update(@PathVariable UUID id, @Valid @RequestBody ClientRequest request) {
        return clients.update(id, request);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public ClientDetail changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusChangeRequest request) {
        return clients.changeStatus(id, request);
    }

    @GetMapping("/{id}/status-history")
    @PreAuthorize("hasAuthority('CLIENT_READ')")
    public List<StatusHistoryEntry> history(@PathVariable UUID id) {
        return clients.history(id);
    }

    // ---- contacts ----

    @GetMapping("/{id}/contacts")
    @PreAuthorize("hasAuthority('CLIENT_READ')")
    public List<ContactDto> contacts(@PathVariable UUID id) {
        return records.contacts(id);
    }

    @PostMapping("/{id}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public ContactDto addContact(@PathVariable UUID id, @Valid @RequestBody ContactRequest request) {
        return records.saveContact(id, null, request);
    }

    @PutMapping("/{id}/contacts/{contactId}")
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public ContactDto updateContact(@PathVariable UUID id, @PathVariable UUID contactId,
                                    @Valid @RequestBody ContactRequest request) {
        return records.saveContact(id, contactId, request);
    }

    @DeleteMapping("/{id}/contacts/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public void deleteContact(@PathVariable UUID id, @PathVariable UUID contactId) {
        records.deleteContact(id, contactId);
    }

    // ---- addresses ----

    @GetMapping("/{id}/addresses")
    @PreAuthorize("hasAuthority('CLIENT_READ')")
    public List<AddressDto> addresses(@PathVariable UUID id) {
        return records.addresses(id);
    }

    @PostMapping("/{id}/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public AddressDto addAddress(@PathVariable UUID id, @Valid @RequestBody AddressRequest request) {
        return records.saveAddress(id, null, request);
    }

    @PutMapping("/{id}/addresses/{addressId}")
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public AddressDto updateAddress(@PathVariable UUID id, @PathVariable UUID addressId,
                                    @Valid @RequestBody AddressRequest request) {
        return records.saveAddress(id, addressId, request);
    }

    @DeleteMapping("/{id}/addresses/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public void deleteAddress(@PathVariable UUID id, @PathVariable UUID addressId) {
        records.deleteAddress(id, addressId);
    }

    // ---- GST registrations ----

    @GetMapping("/{id}/gst-registrations")
    @PreAuthorize("hasAuthority('CLIENT_READ')")
    public List<GstRegistrationDto> gstRegistrations(@PathVariable UUID id) {
        return records.gstRegistrations(id);
    }

    @PostMapping("/{id}/gst-registrations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public GstRegistrationDto addGst(@PathVariable UUID id, @Valid @RequestBody GstRegistrationRequest request) {
        return records.saveGstRegistration(id, null, request);
    }

    @PutMapping("/{id}/gst-registrations/{regId}")
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public GstRegistrationDto updateGst(@PathVariable UUID id, @PathVariable UUID regId,
                                        @Valid @RequestBody GstRegistrationRequest request) {
        return records.saveGstRegistration(id, regId, request);
    }

    // ---- bank accounts ----

    @GetMapping("/{id}/bank-accounts")
    @PreAuthorize("hasAuthority('CLIENT_READ')")
    public List<BankAccountDto> bankAccounts(@PathVariable UUID id) {
        return records.bankAccounts(id);
    }

    @PostMapping("/{id}/bank-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public BankAccountDto addBank(@PathVariable UUID id, @Valid @RequestBody BankAccountRequest request) {
        return records.saveBankAccount(id, null, request);
    }

    @PutMapping("/{id}/bank-accounts/{accountId}")
    @PreAuthorize("hasAuthority('CLIENT_WRITE')")
    public BankAccountDto updateBank(@PathVariable UUID id, @PathVariable UUID accountId,
                                     @Valid @RequestBody BankAccountRequest request) {
        return records.saveBankAccount(id, accountId, request);
    }
}
