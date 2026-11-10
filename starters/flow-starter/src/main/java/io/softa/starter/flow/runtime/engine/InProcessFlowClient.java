package io.softa.starter.flow.runtime.engine;

import java.util.Map;

import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowLaunchService;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * The single {@link FlowClient} implementation — a thin in-process adapter that resolves
 * {@code actorId} / {@code initiatorId} with {@code FlowActors.currentUserId()}, as the REST
 * controllers do, and makes the same service calls as {@code FlowInstanceController}.
 * No new logic, no serialization, no signing — the monolith pays nothing.
 * {@code FlowAutoConfiguration} registers it only when the host provides no {@link FlowClient}.
 */
public class InProcessFlowClient implements FlowClient {

    private final FlowLaunchService launch;

    public InProcessFlowClient(FlowLaunchService launch) {
        this.launch = launch;
    }

    @Override
    public FlowExecutionState submitRecord(String modelName, String rowId) {
        return launch.submitRecord(modelName, rowId, currentUserId());
    }

    @Override
    public FlowApprovalPreview previewValues(String modelName, Map<String, Object> values) {
        return launch.previewValues(modelName, values, currentUserId());
    }

    @Override
    public FlowExecutionState withdrawRecord(String modelName, String rowId, String comment) {
        return launch.withdrawRecord(modelName, rowId, currentUserId(), comment);
    }

    @Override
    public void cancelRecord(String modelName, String rowId) {
        launch.cancelRecord(modelName, rowId);
    }
}
