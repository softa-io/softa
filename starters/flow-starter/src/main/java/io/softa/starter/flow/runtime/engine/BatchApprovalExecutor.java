package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.dto.FlowBatchActionResult;
import io.softa.starter.flow.dto.FlowBatchActionResult.Item;
import io.softa.starter.flow.dto.FlowBatchActionResult.Outcome;
import io.softa.starter.flow.runtime.api.AbstractFlowNodeRequest;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowBatchActionRequest;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.api.FlowReturnRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;

/**
 * Runs one approver action — approve, return or reject — over several of the caller's pending tasks. Each task
 * is its own engine call and so its own transaction: one refused task changes nothing for the others, and
 * each comes back with its own outcome. Every engine rule holds as for a single action — it must be the
 * caller's turn on the step, a return or rejection needs its comment.
 *
 * <p>A batch fills in nothing, so a task is skipped, before the engine is asked, when its step makes a field
 * required (the same rule that keeps its row out of a selection), and a return when its step allows none. Read
 * from the revision the instance runs on, never from the record.
 */
@Slf4j
@Component
public class BatchApprovalExecutor {

    public enum Action { APPROVE, RETURN, REJECT }

    private final FlowRuntimeEngine runtimeEngine;

    private final FlowBundleRegistry bundleRegistry;

    public BatchApprovalExecutor(FlowRuntimeEngine runtimeEngine, FlowBundleRegistry bundleRegistry) {
        this.runtimeEngine = runtimeEngine;
        this.bundleRegistry = bundleRegistry;
    }

    public FlowBatchActionResult execute(Action action, FlowBatchActionRequest request, String actorId) {
        List<Item> items = new ArrayList<>(request.getTasks().size());
        for (FlowBatchActionRequest.Task task : request.getTasks()) {
            items.add(executeOne(action, task, request.getComment(), actorId));
        }
        return FlowBatchActionResult.of(items);
    }

    private Item executeOne(Action action, FlowBatchActionRequest.Task task, String comment, String actorId) {
        try {
            String skipped = stepOf(task).map(step -> skipReason(action, step.definition(), step.node())).orElse(null);
            if (skipped != null) {
                return new Item(task.getInstanceId(), task.getNodeId(), Outcome.SKIPPED, skipped);
            }
            switch (action) {
                case APPROVE -> runtimeEngine.approve(fill(new FlowApproveRequest(), task, comment, actorId));
                case RETURN -> runtimeEngine.returnApproval(fill(new FlowReturnRequest(), task, comment, actorId));
                case REJECT -> runtimeEngine.reject(fill(new FlowRejectRequest(), task, comment, actorId));
            }
            return new Item(task.getInstanceId(), task.getNodeId(), Outcome.SUCCEEDED, null);
        } catch (RuntimeException e) {
            log.info("Batch {} refused on instance {} step {}: {}", action, task.getInstanceId(), task.getNodeId(),
                    e.getMessage());
            return new Item(task.getInstanceId(), task.getNodeId(), Outcome.FAILED, e.getMessage());
        }
    }

    /** A step and the revision that compiled it. */
    private record Step(CompiledFlowDefinition definition, CompiledFlowNode node) {
    }

    /** The step as the instance's revision compiled it; empty when the instance or step is unknown — the engine says why. */
    private Optional<Step> stepOf(FlowBatchActionRequest.Task task) {
        return runtimeEngine.getInstance(task.getInstanceId())
                .flatMap(state -> Optional.ofNullable(state.getBundleId()))
                .flatMap(bundleRegistry::getByBundleId)
                .flatMap(definition -> Optional.ofNullable(definition.getNodeIndex().get(task.getNodeId()))
                        .map(node -> new Step(definition, node)));
    }

    private static String skipReason(Action action, CompiledFlowDefinition definition, CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig)) {
            return null;
        }
        if (!FormPermissionService.requiredFields(node).isEmpty()) {
            return "This step has fields to fill in: open it to act on it.";
        }
        if (action == Action.RETURN && !Boolean.TRUE.equals(definition.getReturnEnabled())) {
            return "This approval does not allow returns.";
        }
        return null;
    }

    private static <T extends AbstractFlowNodeRequest> T fill(
            T request, FlowBatchActionRequest.Task task, String comment, String actorId) {
        request.setInstanceId(task.getInstanceId());
        request.setNodeId(task.getNodeId());
        request.setComment(comment);
        request.setActorId(actorId);
        return request;
    }
}
