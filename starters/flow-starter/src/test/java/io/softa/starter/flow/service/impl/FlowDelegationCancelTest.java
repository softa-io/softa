package io.softa.starter.flow.service.impl;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Cancelling runs past row scope (no business role is granted {@code FlowDelegation}), so the service
 * itself confines it to the requester's own rules.
 */
class FlowDelegationCancelTest {

    private final FlowDelegationServiceImpl service = spy(new FlowDelegationServiceImpl());

    @Test
    void theDelegatorCancelsTheirOwnRule() {
        doReturn(Optional.of(delegation("alice"))).when(service).getById(5L);
        doReturn(true).when(service).updateOne(any(FlowDelegation.class));

        service.cancelDelegation(5L, "alice");

        ArgumentCaptor<FlowDelegation> saved = ArgumentCaptor.forClass(FlowDelegation.class);
        verify(service).updateOne(saved.capture());
        assertEquals(Boolean.FALSE, saved.getValue().getActive());
    }

    @Test
    void anotherUsersRuleIsRefused() {
        doReturn(Optional.of(delegation("alice"))).when(service).getById(5L);

        assertThrows(FlowAuthorizationException.class, () -> service.cancelDelegation(5L, "mallory"));
        verify(service, never()).updateOne(any(FlowDelegation.class));
        verify(service, never()).updateOne(any(FlowDelegation.class), anyBoolean());
    }

    @Test
    void aMissingRuleIsRefusedTheSameWay() {
        doReturn(Optional.empty()).when(service).getById(5L);

        assertThrows(FlowAuthorizationException.class, () -> service.cancelDelegation(5L, "alice"));
    }

    private static FlowDelegation delegation(String delegatorId) {
        FlowDelegation delegation = new FlowDelegation();
        delegation.setId(5L);
        delegation.setDelegatorId(delegatorId);
        delegation.setActive(true);
        return delegation;
    }
}
