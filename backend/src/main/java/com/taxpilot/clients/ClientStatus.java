package com.taxpilot.clients;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Client onboarding / lifecycle states and the only transitions allowed between them. */
public enum ClientStatus {
    DRAFT,
    DOCUMENTS_PENDING,
    UNDER_VERIFICATION,
    READY_FOR_APPROVAL,
    ACTIVE,
    ON_HOLD,
    CLOSED;

    private static final Map<ClientStatus, Set<ClientStatus>> TRANSITIONS = Map.of(
            DRAFT, EnumSet.of(DOCUMENTS_PENDING, UNDER_VERIFICATION, CLOSED),
            DOCUMENTS_PENDING, EnumSet.of(UNDER_VERIFICATION, CLOSED),
            UNDER_VERIFICATION, EnumSet.of(DOCUMENTS_PENDING, READY_FOR_APPROVAL, CLOSED),
            READY_FOR_APPROVAL, EnumSet.of(ACTIVE, UNDER_VERIFICATION, CLOSED),
            ACTIVE, EnumSet.of(ON_HOLD, CLOSED),
            ON_HOLD, EnumSet.of(ACTIVE, CLOSED),
            CLOSED, EnumSet.noneOf(ClientStatus.class));

    public Set<ClientStatus> allowedTargets() {
        return TRANSITIONS.get(this);
    }

    public boolean canMoveTo(ClientStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /** PAN and entity type may only change before the client is first activated. */
    public boolean identityEditable() {
        return this == DRAFT || this == DOCUMENTS_PENDING || this == UNDER_VERIFICATION;
    }
}
