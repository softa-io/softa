package io.softa.starter.flow.runtime.engine;

import java.util.Map;

import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * The single {@link FlowClient} implementation — a thin in-process adapter that resolves
 * {@code actorId} / {@code initiatorId} with {@code FlowActors.currentUserId()}, as the REST
 * controllers do, and makes the same engine and service calls as {@code FlowInstanceController}.
 * No new logic, no serialization, no signing — the monolith pays nothing.
 * {@code FlowAutoConfiguration} registers it only when the host provides no {@link FlowClient}.
 */
public class InProcessFlowClient implements FlowClient {

    private final FlowRuntimeEngine engine;
    private final FlowLaunchService launch;
    private final FlowInstanceService instances;
    private final FlowInstanceAccessGuard accessGuard;

    public InProcessFlowClient(FlowRuntimeEngine engine,
                               FlowLaunchService launch,
                               FlowInstanceService instances,
                               FlowInstanceAccessGuard accessGuard) {
        this.engine = engine;
        this.launch = launch;
        this.instances = instances;
        this.accessGuard = accessGuard;
    }

    @Override
    public FlowExecutionState start(FlowStartRequest r) {
        r.setInitiatorId(currentUserId());
        return engine.startAutomation(r);
    }

    @Override
    public FlowExecutionState submitRecord(String modelName, String rowId) {
        return launch.submitRecord(modelName, rowId, currentUserId());
    }

    @Override
    public FlowApprovalPreview previewRecord(String modelName, String rowId) {
        return launch.previewRecord(modelName, rowId, currentUserId());
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
        launch.cancelRecord(modelName, rowId, currentUserId());
    }

    @Override
    public FlowRecordApprovalsView recordApprovals(String modelName, String rowId) {
        String requesterId = currentUserId();
        return instances.recordApprovals(modelName, rowId, history -> {
            accessGuard.requireRecordViewer(modelName, rowId, history, requesterId);
            accessGuard.markViewable(modelName, rowId, history, requesterId);
        });
    }
}
