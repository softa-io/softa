package io.softa.starter.flow.runtime.store;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowExecutionStateMapperTest {

    /**
     * bundleId/flowId must survive the persistence round trip — approval and
     * resume actions on a DB-reloaded instance resolve the compiled definition
     * through {@code state.getBundleId()}.
     */
    @Test
    void roundTrip_carriesBundleAndFlowIds() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("inst-1")
                .version(7)
                .bundleId(11L)
                .flowId("22")
                .status(FlowExecutionStatus.RUNNING)
                .variables(Map.of("k", "v"))
                .build();

        FlowInstance entity = FlowExecutionStateMapper.toEntity(state);
        assertEquals(7, entity.getVersion());
        assertEquals(11L, entity.getBundleId());
        assertEquals("22", entity.getFlowId());

        FlowExecutionState restored = FlowExecutionStateMapper.toState(entity);
        assertEquals(7, restored.getVersion());
        assertEquals(11L, restored.getBundleId());
        assertEquals("22", restored.getFlowId());
        assertEquals("inst-1", restored.getInstanceId());
        assertEquals(FlowExecutionStatus.RUNNING, restored.getStatus());
        assertEquals("v", restored.getVariables().get("k"));
    }

    /** A state read from its row, or saved as one, updates that row: the id goes along, through a copy too. */
    @Test
    void theRowIdGoesAlongWithTheState() {
        FlowInstance row = FlowExecutionStateMapper.toEntity(FlowExecutionState.builder().instanceId("inst-1").build());
        row.setId(9L);

        FlowExecutionState loaded = FlowExecutionStateMapper.toState(row);

        assertEquals(9L, loaded.getPersistedId());
        assertEquals(9L, FlowExecutionStateMapper.copyOf(loaded).getPersistedId());
        assertEquals(9L, FlowExecutionStateMapper.toEntity(loaded).getId());
    }

    /**
     * A save writes what the state holds, nulls included, and no other field: every field the mapper sets is among
     * the saved ones, and a field written apart from the state — superseded — is not.
     */
    @Test
    void theSavedFieldsAreExactlyTheOnesTheStateHolds() throws IllegalAccessException {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("inst-1").version(3).persistedId(9L).bundleId(11L).flowId("22").flowRevision(2)
                .title("Leave").modelName("LeaveRequest").rowId("7").initiatorId("100").recordApproval(true)
                .subjectId(5L).departmentId(6L).effectiveDate(LocalDate.of(2026, 11, 11)).recordCode("LR-1")
                .summary("3 days").triggerEventId("e1").status(FlowExecutionStatus.WAITING).errorMessage("boom")
                .failedNodeId("n1").retryAfterNodeId("n0").variables(Map.of("k", "v")).waitTokens(List.of())
                .completedNodeIds(List.of("start")).pendingApprovals(List.of()).joinArrivalCounts(Map.of())
                .returnData(Map.of("r", 1))
                .build();

        FlowInstance entity = FlowExecutionStateMapper.toEntity(state);

        Set<String> set = new TreeSet<>();
        for (Field field : FlowInstance.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            if (field.get(entity) != null) {
                set.add(field.getName());
            }
        }
        set.remove("id");
        assertEquals(new TreeSet<>(FlowExecutionStateMapper.STATE_FIELDS), set);
    }
}
