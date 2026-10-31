package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A delegation rule is in force from its creation — one stored without the flag never routed a task — routes the
 * delegator's tasks to someone else, and ends after it starts. The rule the delegator makes and the one an
 * administrator makes for them are checked alike.
 */
class FlowDelegationCreateTest {

    private final FlowDelegationServiceImpl service = spy(new FlowDelegationServiceImpl());

    @Test
    void aNewRuleIsInForce() {
        doReturn(9L).when(service).createOne(any(FlowDelegation.class));

        FlowDelegation created = service.createDelegation(rule("mgr", "dlg",
                LocalDateTime.of(2026, 11, 2, 10, 0), LocalDateTime.of(2026, 11, 3, 10, 0)));

        ArgumentCaptor<FlowDelegation> saved = ArgumentCaptor.forClass(FlowDelegation.class);
        verify(service).createOne(saved.capture());
        assertEquals(Boolean.TRUE, saved.getValue().getActive());
        assertEquals(9L, created.getId());
    }

    @Test
    void aRuleWithoutTimesIsInForceToo() {
        doReturn(9L).when(service).createOne(any(FlowDelegation.class));

        assertEquals(Boolean.TRUE, service.createDelegation(rule("mgr", "dlg", null, null)).getActive());
    }

    @Test
    void noOneDelegatesToThemselves() {
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.createDelegation(rule("dlg", "dlg", null, null)));

        assertEquals("The delegate must be someone else", refused.getMessage());
        verify(service, never()).createOne(any(FlowDelegation.class));
    }

    @Test
    void aRuleEndsAfterItStarts() {
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.createDelegation(rule("mgr", "dlg",
                        LocalDateTime.of(2026, 11, 2, 10, 0), LocalDateTime.of(2026, 11, 1, 10, 0))));

        assertEquals("The delegation must end after it starts", refused.getMessage());
        verify(service, never()).createOne(any(FlowDelegation.class));
    }

    @Test
    void aRuleNamesItsDelegate() {
        assertThrows(FlowActionValidationException.class, () -> service.createDelegation(rule("mgr", " ", null, null)));
        verify(service, never()).createOne(any(FlowDelegation.class));
    }

    private static FlowDelegation rule(String delegatorId, String delegateId, LocalDateTime start, LocalDateTime end) {
        FlowDelegation delegation = new FlowDelegation();
        delegation.setDelegatorId(delegatorId);
        delegation.setDelegateId(delegateId);
        delegation.setScope("All");
        delegation.setStartTime(start);
        delegation.setEndTime(end);
        return delegation;
    }
}
