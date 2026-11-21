package com.taxpilot.invoicing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.ClientService;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.validation.IndianIds;
import com.taxpilot.common.validation.IndianStates;
import com.taxpilot.invoicing.InvoicingRepositories.PartyRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Service
public class PartyService {

    public record PartyRequest(@NotNull UUID clientId, @NotNull Party.Type partyType,
                               @NotBlank @Size(max = 200) String name, String gstin, String pan, String stateCode,
                               @Email @Size(max = 254) String email, @Size(max = 20) String phone,
                               @Size(max = 500) String address, @Min(0) @Max(365) Integer creditDays, Boolean active,
                               Long version) {
    }

    public record PartyDto(UUID id, Party.Type partyType, String name, String gstin, String pan, String stateCode,
                           String stateName, String email, String phone, String address, int creditDays,
                           boolean active, long version) {
        static PartyDto of(Party p) {
            return new PartyDto(p.getId(), p.getPartyType(), p.getName(), p.getGstin(), p.getPan(), p.getStateCode(),
                    p.getStateCode() == null ? null : IndianStates.CODES.get(p.getStateCode()), p.getEmail(),
                    p.getPhone(), p.getAddress(), p.getCreditDays(), p.isActive(), p.getVersion());
        }
    }

    private final PartyRepository parties;
    private final ClientService clients;
    private final AuditService audit;

    public PartyService(PartyRepository parties, ClientService clients, AuditService audit) {
        this.parties = parties;
        this.clients = clients;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PartyDto> list(UUID clientId) {
        clients.find(clientId);
        return parties.findByClientIdOrderByNameAsc(clientId).stream().map(PartyDto::of).toList();
    }

    @Transactional
    public PartyDto save(UUID id, PartyRequest r) {
        clients.findOpen(r.clientId());
        Party p;
        if (id == null) {
            p = new Party(r.clientId());
        } else {
            p = find(id);
            if (!p.getClientId().equals(r.clientId())) throw ApiException.notFound("Party");
            if (r.version() == null || r.version() != p.getVersion()) {
                throw ApiException.conflict("STALE_VERSION", "This record was changed by someone else. Reload and try again.");
            }
        }
        String gstin = blank(IndianIds.normalise(r.gstin()));
        String pan = blank(IndianIds.normalise(r.pan()));
        String state = blank(r.stateCode());
        if (gstin != null) {
            if (!IndianIds.isValidGstin(gstin)) throw ApiException.field("gstin", "GSTIN is invalid (format, state code or check digit)");
            String fromGstin = gstin.substring(0, 2);
            if (state != null && !state.equals(fromGstin)) {
                throw ApiException.field("stateCode", "State must match the GSTIN state code (" + fromGstin + ")");
            }
            state = fromGstin;
            if (pan == null) pan = IndianIds.panFromGstin(gstin);
            final String g = gstin;
            parties.findByClientIdAndGstin(r.clientId(), g).filter(o -> !o.getId().equals(p.getId())).ifPresent(o -> {
                throw ApiException.field("gstin", "Another party already uses this GSTIN");
            });
        }
        if (pan != null && !IndianIds.isValidPan(pan)) throw ApiException.field("pan", "PAN must be in the format AAAAA9999A");
        if (state != null && !IndianStates.isValid(state)) throw ApiException.field("stateCode", "Unknown state code");
        p.update(r.partyType(), r.name().trim(), gstin, pan, state, blank(r.email()), blank(r.phone()),
                blank(r.address()), (short) (r.creditDays() == null ? 30 : r.creditDays()),
                r.active() == null || r.active());
        parties.save(p);
        audit.record(id == null ? "PARTY_CREATED" : "PARTY_UPDATED", "PARTY", p.getId(), r.clientId(),
                Map.of("name", p.getName(), "type", p.getPartyType().name()));
        return PartyDto.of(p);
    }

    public Party find(UUID id) {
        return parties.findById(id).orElseThrow(() -> ApiException.notFound("Party"));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @RestController
    @RequestMapping("/api/v1/parties")
    public static class PartyController {
        private final PartyService service;

        public PartyController(PartyService service) {
            this.service = service;
        }

        @GetMapping
        @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
        public List<PartyDto> list(@RequestParam UUID clientId) {
            return service.list(clientId);
        }

        @PostMapping
        @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
        public PartyDto create(@Valid @RequestBody PartyRequest r) {
            return service.save(null, r);
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
        public PartyDto update(@PathVariable UUID id, @Valid @RequestBody PartyRequest r) {
            return service.save(id, r);
        }
    }
}
