package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.state.PendingApproval;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A step's approval threshold always lets its vote decide it: no more approvals than it has approvers. Before, a "two
 * of them" step whose second approver had left asked for two from one person. A rejection needs no threshold: one
 * approver's rejection rejects the approval.
 */
class ApprovalThresholdCapTest {

    private final ApproverResolutionService resolution = new ApproverResolutionService();

    private static CompiledFlowNode step(ApprovalNodeConfig config) {
        return CompiledFlowNode.builder().nodeId("n1").type(FlowNodeType.APPROVAL).parsedConfig(config).build();
    }

    @Test
    void aStepAskingMorePeopleThanItHasLeftAsksThoseItHas() {
        // "Two of A and B": B left, and was dropped as the step started.
        CompiledFlowNode node = step(ApprovalNodeConfig.builder()
                .approvalMode(VoteThresholdMode.MIN_COUNT).minCount(2).build());

        assertEquals(1, resolution.resolveRequiredApprovalCount(node, List.of("A")));
        assertEquals(2, resolution.resolveRequiredApprovalCount(node, List.of("A", "B")));
    }

    @Test
    void aStepEveryoneMustApproveCountsEveryone() {
        CompiledFlowNode node = step(ApprovalNodeConfig.builder().approvalMode(VoteThresholdMode.UNANIMOUS).build());

        assertEquals(3, resolution.resolveRequiredApprovalCount(node, List.of("A", "B", "C")));
    }

    @Test
    void mergingTwoApproversCapsTheApprovalsTheStepNeeds() {
        PendingApproval pending = PendingApproval.builder()
                .approvers(new ArrayList<>(List.of("A", "B", "C")))
                .totalApproverCount(3)
                .requiredApprovalCount(3)
                .approvedActors(new ArrayList<>())
                .build();

        // C's place goes to B, already an approver of the step: two people left.
        new ApprovalLifecycleService().mergeApprover(pending, "C", "B");

        assertEquals(List.of("A", "B"), pending.getApprovers());
        assertEquals(2, pending.getRequiredApprovalCount());
    }
}
