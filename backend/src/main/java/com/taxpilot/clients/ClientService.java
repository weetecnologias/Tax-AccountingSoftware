package com.taxpilot.clients;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.ClientDtos.ClientDetail;
import com.taxpilot.clients.ClientDtos.ClientRequest;
import com.taxpilot.clients.ClientDtos.ClientSummary;
import com.taxpilot.clients.ClientDtos.Counts;
import com.taxpilot.clients.ClientDtos.StatusChangeRequest;
import com.taxpilot.clients.ClientDtos.StatusHistoryEntry;
import com.taxpilot.clients.ClientRepositories.ClientRepository;
import com.taxpilot.clients.ClientRepositories.ContactRepository;
import com.taxpilot.clients.ClientRepositories.GstRegistrationRepository;
import com.taxpilot.clients.ClientRepositories.StatusHistoryRepository;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.Organisations;
import com.taxpilot.common.PageResponse;
import com.taxpilot.common.crypto.BlindIndex;
import com.taxpilot.common.crypto.FieldEncryptor;
import com.taxpilot.common.validation.IndianIds;
import com.taxpilot.documents.DocumentQueries;
import com.taxpilot.security.CurrentUser;
import com.taxpilot.users.User;
import com.taxpilot.users.UserDtos.UserRef;
import com.taxpilot.users.UserRepository;

@Service
public class ClientService {

    private static final int EXPORT_LIMIT = 10_000;

    private final ClientRepository clients;
    private final StatusHistoryRepository history;
    private final ContactRepository contacts;
    private final GstRegistrationRepository gstRegistrations;
    private final UserRepository users;
    private final DocumentQueries documents;
    private final FieldEncryptor encryptor;
    private final BlindIndex blindIndex;
    private final AuditService audit;

    public ClientService(ClientRepository clients, StatusHistoryRepository history, ContactRepository contacts,
                         GstRegistrationRepository gstRegistrations, UserRepository users, DocumentQueries documents,
                         FieldEncryptor encryptor, BlindIndex blindIndex, AuditService audit) {
        this.clients = clients;
        this.history = history;
        this.contacts = contacts;
        this.gstRegistrations = gstRegistrations;
        this.users = users;
        this.documents = documents;
        this.encryptor = encryptor;
        this.blindIndex = blindIndex;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public PageResponse<ClientSummary> list(String q, ClientStatus status, EntityType entityType, Pageable pageable) {
        return PageResponse.of(clients.findAll(filter(q, status, entityType), pageable), this::toSummary);
    }

    @Transactional(readOnly = true)
    public ClientDetail get(UUID id) {
        return toDetail(find(id));
    }

    @Transactional(readOnly = true)
    public Client find(UUID id) {
        return clients.findById(id).orElseThrow(() -> ApiException.notFound("Client"));
    }

    /** Loads a client that may still be changed; closed clients are read-only. */
    @Transactional(readOnly = true)
    public Client findOpen(UUID id) {
        Client client = find(id);
        if (client.getStatus() == ClientStatus.CLOSED) {
            throw ApiException.conflict("CLIENT_CLOSED", "This client is closed and can no longer be changed");
        }
        return client;
    }

    /** Decrypted PAN, for internal rule checks only (never logged or audited). */
    public String panOf(Client client) {
        return encryptor.decryptString(client.getPanEncrypted());
    }

    @Transactional(readOnly = true)
    public List<StatusHistoryEntry> history(UUID clientId) {
        find(clientId);
        return history.findByClientIdOrderByChangedAtDesc(clientId).stream()
                .map(h -> new StatusHistoryEntry(h.getId(), h.getFromStatus(), h.getToStatus(),
                        UserRef.of(users.findById(h.getChangedBy()).orElse(null)), h.getComment(), h.getChangedAt()))
                .toList();
    }

    @Transactional
    public String exportCsv(String q, ClientStatus status, EntityType entityType) {
        List<Client> rows = clients.findAll(filter(q, status, entityType)).stream()
                .sorted((a, b) -> a.getClientCode().compareTo(b.getClientCode()))
                .limit(EXPORT_LIMIT).toList();
        StringWriter out = new StringWriter();
        out.append("Client Code,Display Name,Legal Name,Entity Type,PAN (masked),TAN,Status,Accountant,Reviewer,"
                + "Email,Phone,Created At\r\n");
        for (Client c : rows) {
            out.append(String.join(",", csv(c.getClientCode()), csv(c.getDisplayName()), csv(c.getLegalName()),
                    csv(c.getEntityType().name()), csv(IndianIds.mask(c.getPanLast4(), 10)), csv(c.getTan()),
                    csv(c.getStatus().name()),
                    csv(c.getAccountant() == null ? null : c.getAccountant().getFullName()),
                    csv(c.getReviewer() == null ? null : c.getReviewer().getFullName()), csv(c.getEmail()),
                    csv(c.getPhone()), csv(c.getCreatedAt().toString()))).append("\r\n");
        }
        audit.record("CLIENTS_EXPORTED", "CLIENT", null, null, Map.of("rows", rows.size(),
                "filters", Map.of("q", q == null ? "" : q, "status", status == null ? "" : status.name(),
                        "entityType", entityType == null ? "" : entityType.name())));
        return out.toString();
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    public ClientDetail create(ClientRequest req) {
        CurrentUser me = CurrentUser.require();
        String pan = validatePan(req.pan(), req.entityType());
        String panHash = blindIndex.of(pan);
        if (clients.findByOrganisationIdAndPanHash(Organisations.DEFAULT_ID, panHash).isPresent()) {
            throw panTaken();
        }
        Client client = new Client("CL" + clients.nextClientCode(), me.id());
        client.setPan(encryptor.encryptString(pan), panHash, pan.substring(6));
        applyEditableFields(client, req);
        clients.save(client);
        history.save(new ClientStatusHistory(client.getId(), null, ClientStatus.DRAFT, me.id(), "Client created"));
        audit.record("CLIENT_CREATED", "CLIENT", client.getId(), client.getId(),
                Map.of("clientCode", client.getClientCode(), "displayName", client.getDisplayName(),
                        "entityType", client.getEntityType().name()));
        return toDetail(client);
    }

    @Transactional
    public ClientDetail update(UUID id, ClientRequest req) {
        Client client = findOpen(id);
        if (req.version() == null || req.version() != client.getVersion()) {
            throw ApiException.conflict("STALE_VERSION", "This record was changed by someone else. Reload and try again.");
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        String pan = validatePan(req.pan(), req.entityType());
        String panHash = blindIndex.of(pan);
        boolean panChanged = !panHash.equals(client.getPanHash());
        if ((panChanged || req.entityType() != client.getEntityType()) && !client.getStatus().identityEditable()) {
            throw ApiException.conflict("IDENTITY_LOCKED",
                    "PAN and entity type cannot change after the client has been sent for approval");
        }
        if (panChanged) {
            clients.findByOrganisationIdAndPanHash(Organisations.DEFAULT_ID, panHash)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw panTaken();
                    });
            if (gstRegistrations.countByClientId(id) > 0) {
                throw ApiException.field("pan", "Remove the client's GST registrations before changing the PAN");
            }
            client.setPan(encryptor.encryptString(pan), panHash, pan.substring(6));
            changed.put("pan", "changed");
        }
        trackChanges(client, req, changed);
        applyEditableFields(client, req);
        clients.save(client);
        if (!changed.isEmpty()) {
            audit.record("CLIENT_UPDATED", "CLIENT", id, id, changed);
        }
        return toDetail(client);
    }

    @Transactional
    public ClientDetail changeStatus(UUID id, StatusChangeRequest req) {
        CurrentUser me = CurrentUser.require();
        Client client = find(id);
        ClientStatus from = client.getStatus();
        ClientStatus to = req.targetStatus();
        if (!from.canMoveTo(to)) {
            throw ApiException.conflict("INVALID_TRANSITION", "A client cannot move from " + from + " to " + to);
        }
        switch (to) {
            case READY_FOR_APPROVAL -> {
                if (client.getAccountant() == null || client.getReviewer() == null) {
                    throw ApiException.badRequest("ASSIGNMENT_REQUIRED",
                            "Assign an accountant and a reviewer before sending the client for approval");
                }
            }
            case ACTIVE -> {
                if (from == ClientStatus.READY_FOR_APPROVAL) {
                    requirePermission(me, "CLIENT_APPROVE", "Only an approver can activate a client");
                    UUID submittedBy = history.findFirstByClientIdAndToStatusOrderByChangedAtDesc(id,
                            ClientStatus.READY_FOR_APPROVAL).map(ClientStatusHistory::getChangedBy).orElse(null);
                    if (me.id().equals(submittedBy)) {
                        throw ApiException.forbidden("MAKER_CHECKER",
                                "You submitted this client for approval, so a different user must approve it");
                    }
                }
            }
            case CLOSED -> {
                requirePermission(me, "CLIENT_APPROVE", "Only an approver can close a client");
                if (req.closureReason() == null || req.closureReason().isBlank()) {
                    throw ApiException.field("closureReason", "A closure reason is required");
                }
                long pending = documents.countAwaitingReview(id);
                if (pending > 0) {
                    throw ApiException.conflict("UNRESOLVED_ITEMS", pending
                            + " document(s) are still awaiting review. Approve, reject or archive them first.");
                }
            }
            default -> {
                // other transitions need only CLIENT_WRITE, enforced at the controller
            }
        }
        if (to == ClientStatus.CLOSED) {
            client.close(req.closureReason().trim());
        } else {
            client.setStatus(to);
        }
        clients.save(client);
        String comment = to == ClientStatus.CLOSED ? req.closureReason().trim() : trimToNull(req.comment());
        history.save(new ClientStatusHistory(id, from, to, me.id(), comment));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from.name());
        details.put("to", to.name());
        if (comment != null) details.put("comment", comment);
        audit.record(to == ClientStatus.ACTIVE && from == ClientStatus.READY_FOR_APPROVAL ? "CLIENT_ACTIVATED"
                : to == ClientStatus.CLOSED ? "CLIENT_CLOSED" : "CLIENT_STATUS_CHANGED", "CLIENT", id, id, details);
        return toDetail(client);
    }

    // ---------------------------------------------------------------- helpers

    private void applyEditableFields(Client client, ClientRequest req) {
        client.setDisplayName(req.displayName().trim());
        client.setLegalName(req.legalName().trim());
        client.setEntityType(req.entityType());
        client.setTan(validateTan(req.tan()));
        client.setDateOfIncorporation(req.dateOfIncorporation());
        client.setEmail(trimToNull(req.email()));
        client.setPhone(trimToNull(req.phone()));
        client.setFyStartMonth(req.fyStartMonth() == null ? 4 : req.fyStartMonth().shortValue());
        client.setAccountingMethod(req.accountingMethod() == null ? Client.AccountingMethod.ACCRUAL
                : req.accountingMethod());
        client.setNotes(trimToNull(req.notes()));
        User accountant = req.accountantId() == null ? null : activeUser(req.accountantId(), "accountantId");
        User reviewer = req.reviewerId() == null ? null : activeUser(req.reviewerId(), "reviewerId");
        if (reviewer != null && !reviewer.permissionCodes().contains("CLIENT_APPROVE")) {
            throw ApiException.field("reviewerId", "The reviewer must be a user who can approve clients");
        }
        if (accountant != null && reviewer != null && accountant.getId().equals(reviewer.getId())) {
            throw ApiException.field("reviewerId", "The reviewer must be a different person from the accountant");
        }
        client.setAccountant(accountant);
        client.setReviewer(reviewer);
    }

    private void trackChanges(Client c, ClientRequest r, Map<String, Object> changed) {
        if (!Objects.equals(c.getDisplayName(), r.displayName().trim())) changed.put("displayName", r.displayName().trim());
        if (!Objects.equals(c.getLegalName(), r.legalName().trim())) changed.put("legalName", r.legalName().trim());
        if (c.getEntityType() != r.entityType()) changed.put("entityType", r.entityType().name());
        if (!Objects.equals(c.getTan(), validateTan(r.tan()))) changed.put("tan", "changed");
        if (!Objects.equals(c.getEmail(), trimToNull(r.email()))) changed.put("email", "changed");
        if (!Objects.equals(c.getPhone(), trimToNull(r.phone()))) changed.put("phone", "changed");
        UUID oldAcc = c.getAccountant() == null ? null : c.getAccountant().getId();
        UUID oldRev = c.getReviewer() == null ? null : c.getReviewer().getId();
        if (!Objects.equals(oldAcc, r.accountantId())) changed.put("accountantId", String.valueOf(r.accountantId()));
        if (!Objects.equals(oldRev, r.reviewerId())) changed.put("reviewerId", String.valueOf(r.reviewerId()));
    }

    private User activeUser(UUID id, String field) {
        User u = users.findById(id).orElseThrow(() -> ApiException.field(field, "User not found"));
        if (!u.isActive()) {
            throw ApiException.field(field, "This user is inactive");
        }
        return u;
    }

    static String validatePan(String raw, EntityType type) {
        String pan = IndianIds.normalise(raw);
        if (!IndianIds.isValidPan(pan)) {
            throw ApiException.field("pan", "PAN must be in the format AAAAA9999A");
        }
        if (type != null && !type.acceptsPanHolderType(IndianIds.panHolderType(pan))) {
            throw ApiException.field("pan", "The 4th character of the PAN does not match the entity type "
                    + type.label() + " (expected " + type.panHolderTypes() + ")");
        }
        return pan;
    }

    static String validateTan(String raw) {
        String tan = trimToNull(IndianIds.normalise(raw));
        if (tan != null && !IndianIds.isValidTan(tan)) {
            throw ApiException.field("tan", "TAN must be in the format AAAA99999A");
        }
        return tan;
    }

    private static ApiException panTaken() {
        return new ApiException(org.springframework.http.HttpStatus.CONFLICT, "PAN_EXISTS",
                "A client with this PAN already exists",
                List.of(new ApiException.FieldError("pan", "A client with this PAN already exists")));
    }

    private static void requirePermission(CurrentUser me, String permission, String message) {
        if (!me.has(permission)) {
            throw ApiException.forbidden("FORBIDDEN", message);
        }
    }

    private Specification<Client> filter(String q, ClientStatus status, EntityType entityType) {
        Specification<Client> spec = (r, query, cb) -> cb.conjunction();
        if (q != null && !q.isBlank()) {
            String term = q.trim();
            String asPan = IndianIds.normalise(term);
            if (IndianIds.isValidPan(asPan)) {
                String hash = blindIndex.of(asPan);
                spec = spec.and((r, query, cb) -> cb.equal(r.get("panHash"), hash));
            } else {
                String like = "%" + term.toLowerCase() + "%";
                spec = spec.and((r, query, cb) -> cb.or(
                        cb.like(cb.lower(r.get("displayName")), like),
                        cb.like(cb.lower(r.get("legalName")), like),
                        cb.like(cb.lower(r.get("clientCode")), like)));
            }
        }
        if (status != null) spec = spec.and((r, query, cb) -> cb.equal(r.get("status"), status));
        if (entityType != null) spec = spec.and((r, query, cb) -> cb.equal(r.get("entityType"), entityType));
        return spec;
    }

    private ClientSummary toSummary(Client c) {
        return new ClientSummary(c.getId(), c.getClientCode(), c.getDisplayName(), c.getLegalName(),
                c.getEntityType(), IndianIds.mask(c.getPanLast4(), 10), c.getStatus(), UserRef.of(c.getAccountant()),
                UserRef.of(c.getReviewer()), c.getCreatedAt());
    }

    private ClientDetail toDetail(Client c) {
        CurrentUser me = CurrentUser.orNull();
        boolean reveal = me != null && me.has("CLIENT_PII_READ");
        List<ClientStatus> allowed = new ArrayList<>();
        if (me != null && me.has("CLIENT_WRITE")) {
            Set<ClientStatus> targets = EnumSet.copyOf(c.getStatus().allowedTargets().isEmpty()
                    ? EnumSet.noneOf(ClientStatus.class) : c.getStatus().allowedTargets());
            for (ClientStatus t : targets) {
                boolean needsApprove = t == ClientStatus.CLOSED
                        || (t == ClientStatus.ACTIVE && c.getStatus() == ClientStatus.READY_FOR_APPROVAL);
                if (!needsApprove || me.has("CLIENT_APPROVE")) {
                    allowed.add(t);
                }
            }
        }
        Counts counts = new Counts(gstRegistrations.countByClientId(c.getId()), contacts.countByClientId(c.getId()),
                documents.countForClient(c.getId()), documents.countAwaitingReview(c.getId()));
        return new ClientDetail(c.getId(), c.getClientCode(), c.getDisplayName(), c.getLegalName(), c.getEntityType(),
                reveal ? panOf(c) : IndianIds.mask(c.getPanLast4(), 10), reveal, c.getTan(),
                c.getDateOfIncorporation(), c.getEmail(), c.getPhone(), c.getFyStartMonth(), c.getAccountingMethod(),
                c.getStatus(), UserRef.of(c.getAccountant()), UserRef.of(c.getReviewer()), c.getNotes(),
                c.getClosureReason(), c.getClosedAt(), c.getCreatedAt(), c.getUpdatedAt(), c.getVersion(), allowed,
                counts);
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        // Neutralise spreadsheet formula injection.
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
