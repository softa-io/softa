package io.softa.starter.flow.runtime.store;

import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowExecutionStateMapperTest {

    /**
     * bundleId/designId must survive the persistence round trip — approval and
     * resume actions on a DB-reloaded instance resolve the compiled definition
     * through {@code state.getBundleId()}.
     */
    @Test
    void roundTrip_carriesBundleAndDesignIds() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("inst-1")
                .version(7)
                .bundleId(11L)
                .designId(22L)
                .flowCode("leave-flow")
                .status(FlowExecutionStatus.RUNNING)
                .variables(Map.of("k", "v"))
                .build();

        FlowInstance entity = FlowExecutionStateMapper.toEntity(state, null);
        assertEquals(7, entity.getVersion());
        assertEquals(11L, entity.getBundleId());
        assertEquals(22L, entity.getDesignId());

        FlowExecutionState restored = FlowExecutionStateMapper.toState(entity);
        assertEquals(7, restored.getVersion());
        assertEquals(11L, restored.getBundleId());
        assertEquals(22L, restored.getDesignId());
        assertEquals("inst-1", restored.getInstanceId());
        assertEquals(FlowExecutionStatus.RUNNING, restored.getStatus());
        assertEquals("v", restored.getVariables().get("k"));
    }
}
