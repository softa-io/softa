package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.store.FlowExecutionStateMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Every save reaches the state listeners; only one that moved the instance's status — from the status the action
 * found — reaches the status listeners, once, whatever the action recorded.
 */
class FlowProjectionPublisherTest {

    private final List<String> saves = new ArrayList<>();
    private final List<String> moves = new ArrayList<>();
    private final FlowProjectionPublisher publisher = new FlowProjectionPublisher(
            List.of(state -> saves.add(String.valueOf(state.getStatus()))),
            List.of((state, from) -> moves.add(from + " -> " + state.getStatus())));

    @Test
    void aSaveThatLeavesTheStatusAsTheActionFoundItReachesOnlyTheStateListeners() {
        FlowExecutionState state = loaded(FlowExecutionStatus.COMPLETED);
        // A CC read on an approval that has its result: an entry in the status it found, as the result's CCs are.
        state.getApprovalAuditDelta().add(ApprovalActionAuditEntry.builder().action(ApprovalActionType.READ)
                .statusBefore(FlowExecutionStatus.COMPLETED).statusAfter(FlowExecutionStatus.COMPLETED).build());

        publisher.publish(state);

        assertEquals(List.of("COMPLETED"), saves);
        assertEquals(List.of(), moves);
    }

    @Test
    void aSaveThatMovesTheStatusReachesBothWithTheStatusTheActionFound() {
        FlowExecutionState state = loaded(FlowExecutionStatus.WAITING);
        state.setStatus(FlowExecutionStatus.COMPLETED);
        // The CCs the result sends come last, in the status the approval ended in: they decide nothing here.
        state.getApprovalAuditDelta().add(ApprovalActionAuditEntry.builder().action(ApprovalActionType.APPROVE)
                .statusBefore(FlowExecutionStatus.WAITING).statusAfter(FlowExecutionStatus.RUNNING).build());
        state.getApprovalAuditDelta().add(ApprovalActionAuditEntry.builder().action(ApprovalActionType.CC)
                .statusBefore(FlowExecutionStatus.COMPLETED).statusAfter(FlowExecutionStatus.COMPLETED).build());

        publisher.publish(state);

        assertEquals(List.of("COMPLETED"), saves);
        assertEquals(List.of("WAITING -> COMPLETED"), moves);
    }

    @Test
    void anInstanceSavedForTheFirstTimeMovesFromNoStatus() {
        FlowExecutionState state = FlowExecutionState.builder().instanceId("new")
                .status(FlowExecutionStatus.COMPLETED).approvalAuditDelta(new ArrayList<>()).build();

        publisher.publish(state);

        assertEquals(List.of("null -> COMPLETED"), moves);
    }

    @Test
    void aMoveIsAnnouncedOnceThoughTheActionSavesAgain() {
        FlowExecutionState state = loaded(FlowExecutionStatus.WAITING);
        state.setStatus(FlowExecutionStatus.REJECTED);

        publisher.publish(state);
        publisher.publish(state);

        assertEquals(List.of("REJECTED", "REJECTED"), saves);
        assertEquals(List.of("WAITING -> REJECTED"), moves);
        assertEquals(FlowExecutionStatus.REJECTED, state.getLoadedStatus());
    }

    @Test
    void aLoadedStateKeepsTheStatusItWasReadInAndACopyKeepsTheOneItsOriginalWasRead() {
        FlowExecutionState state = loaded(FlowExecutionStatus.WAITING);
        state.setStatus(FlowExecutionStatus.RUNNING);

        FlowExecutionState copy = FlowExecutionStateMapper.copyOf(state);

        assertEquals(FlowExecutionStatus.WAITING, state.getLoadedStatus());
        assertEquals(FlowExecutionStatus.RUNNING, copy.getStatus());
        assertEquals(FlowExecutionStatus.WAITING, copy.getLoadedStatus());
        assertNull(FlowExecutionState.builder().build().getLoadedStatus());
    }

    /** An instance as the store reads it, in {@code status}. */
    private static FlowExecutionState loaded(FlowExecutionStatus status) {
        FlowInstance row = new FlowInstance();
        row.setInstanceId("instance-1");
        row.setStatus(status);
        return FlowExecutionStateMapper.toState(row);
    }
}
