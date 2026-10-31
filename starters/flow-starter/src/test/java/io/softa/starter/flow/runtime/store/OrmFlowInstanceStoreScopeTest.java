package io.softa.starter.flow.runtime.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.engine.FlowProjectionPublisher;
import io.softa.starter.flow.runtime.engine.FlowStateChangeListener;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowExecutionTraceService;
import io.softa.starter.flow.service.FlowInstanceService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The engine's own bookkeeping runs past the caller's data permissions: an approver who holds no grant
 * on the flow models — the norm — must still be able to load the instance they act on, and the action
 * must be able to save it and keep its projections.
 */
class OrmFlowInstanceStoreScopeTest {

    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);

    private final OrmFlowInstanceStore store = new OrmFlowInstanceStore(instanceService,
            mock(FlowExecutionTraceService.class), mock(ApprovalActionLedger.class));

    /** Whether permission checks were on each time the instance service was reached. */
    private final List<Boolean> skipped = new ArrayList<>();

    @Test
    void loadsTheInstancePastTheCallersRowScope() {
        when(instanceService.findByInstanceId("i-1")).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return Optional.of(instance());
        });

        Optional<FlowExecutionState> state = asRequest(() -> store.get("i-1"));

        assertTrue(state.isPresent());
        assertEquals(List.of(true), skipped);
    }

    @Test
    void savesTheInstancePastTheCallersRowScope() {
        when(instanceService.findByInstanceId("i-1")).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return Optional.of(instance());
        });
        when(instanceService.saveInstance(any())).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return invocation.getArgument(0);
        });
        FlowExecutionState state = new FlowExecutionState();
        state.setInstanceId("i-1");
        state.setStatus(FlowExecutionStatus.WAITING);

        asRequest(() -> store.save(state));

        assertEquals(List.of(true, true), skipped);
    }

    @Test
    void projectionsRunPastTheCallersRowScope() {
        FlowStateChangeListener listener = state -> skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
        FlowProjectionPublisher publisher = new FlowProjectionPublisher(List.of(listener));

        asRequest(() -> {
            publisher.publish(new FlowExecutionState());
            return null;
        });

        assertEquals(List.of(true), skipped);
    }

    @Test
    void listsWholeStatesThatStillKnowTheirPendingApprovals() {
        FlowInstance waiting = instance();
        waiting.setPendingApprovals("[{\"nodeId\":\"manager\",\"approvers\":[\"2001\"]}]");
        when(instanceService.findStatesByInstanceIds(List.of("i-1"))).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return List.of(waiting);
        });

        List<FlowExecutionState> states = asRequest(() -> store.listByInstanceIds(List.of("i-1")));

        // Read from the summary projection, the state waited on nobody, and My Requests never offered an urge.
        assertEquals(List.of("2001"), states.getFirst().getPendingApprovals().getFirst().getApprovers());
        assertEquals(List.of(true), skipped);
    }

    /** Runs {@code action} as a signed-in user whose permission checks are on, and checks they still are after. */
    private static <T> T asRequest(java.util.function.Supplier<T> action) {
        Context context = new Context();
        context.setUserId(7L);
        return ContextHolder.callWith(context, () -> {
            T result = action.get();
            assertFalse(ContextHolder.getContext().isSkipPermissionCheck(), "the caller's own checks are restored");
            return result;
        });
    }

    private static FlowInstance instance() {
        FlowInstance instance = new FlowInstance();
        instance.setId(1L);
        instance.setInstanceId("i-1");
        instance.setStatus(FlowExecutionStatus.WAITING);
        return instance;
    }
}
