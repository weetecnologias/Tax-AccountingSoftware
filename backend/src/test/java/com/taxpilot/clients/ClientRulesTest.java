package com.taxpilot.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.taxpilot.common.ApiException;

class ClientRulesTest {

    @Test
    void onboardingTransitionsFollowTheStateMachine() {
        assertThat(ClientStatus.DRAFT.canMoveTo(ClientStatus.DOCUMENTS_PENDING)).isTrue();
        assertThat(ClientStatus.DRAFT.canMoveTo(ClientStatus.ACTIVE)).isFalse();
        assertThat(ClientStatus.UNDER_VERIFICATION.canMoveTo(ClientStatus.READY_FOR_APPROVAL)).isTrue();
        assertThat(ClientStatus.READY_FOR_APPROVAL.canMoveTo(ClientStatus.ACTIVE)).isTrue();
        assertThat(ClientStatus.READY_FOR_APPROVAL.canMoveTo(ClientStatus.UNDER_VERIFICATION)).isTrue();
        assertThat(ClientStatus.ACTIVE.canMoveTo(ClientStatus.ON_HOLD)).isTrue();
        assertThat(ClientStatus.ON_HOLD.canMoveTo(ClientStatus.ACTIVE)).isTrue();
        assertThat(ClientStatus.ACTIVE.canMoveTo(ClientStatus.DRAFT)).isFalse();
        assertThat(ClientStatus.CLOSED.allowedTargets()).isEmpty();
    }

    @Test
    void everyOpenStateCanBeClosed() {
        for (ClientStatus s : ClientStatus.values()) {
            if (s != ClientStatus.CLOSED) {
                assertThat(s.canMoveTo(ClientStatus.CLOSED)).as(s.name()).isTrue();
            }
        }
    }

    @Test
    void identityIsLockedFromApprovalOnwards() {
        assertThat(ClientStatus.UNDER_VERIFICATION.identityEditable()).isTrue();
        assertThat(ClientStatus.READY_FOR_APPROVAL.identityEditable()).isFalse();
        assertThat(ClientStatus.ACTIVE.identityEditable()).isFalse();
    }

    @Test
    void panMustMatchEntityType() {
        assertThat(ClientService.validatePan(" aaacx1234c ", EntityType.PRIVATE_LIMITED)).isEqualTo("AAACX1234C");
        assertThat(ClientService.validatePan("AAAPX1234C", EntityType.INDIVIDUAL)).isEqualTo("AAAPX1234C");
        assertThat(ClientService.validatePan("AAATX1234C", EntityType.SOCIETY)).isEqualTo("AAATX1234C");
        assertThatThrownBy(() -> ClientService.validatePan("AAAPX1234C", EntityType.PRIVATE_LIMITED))
                .isInstanceOf(ApiException.class).hasMessageContaining("4th character");
        assertThatThrownBy(() -> ClientService.validatePan("BAD", EntityType.INDIVIDUAL))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void tanIsOptionalButValidated() {
        assertThat(ClientService.validateTan(null)).isNull();
        assertThat(ClientService.validateTan("  ")).isNull();
        assertThat(ClientService.validateTan("muma12345b")).isEqualTo("MUMA12345B");
        assertThatThrownBy(() -> ClientService.validateTan("MUM12345B")).isInstanceOf(ApiException.class);
    }
}
