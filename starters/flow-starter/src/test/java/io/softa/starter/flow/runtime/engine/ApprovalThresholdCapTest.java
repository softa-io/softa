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
 * A step's thresholds always let its vote decide it: no more approvals than it has approvers, and no more rejections
 * than a split vote can reach. Before, a "two of them" step whose second approver had left asked for two from one
 * person, and a step needing two approvals or two rejections out of two people waited for ever on one of each.
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
    void aSplitVoteAlwaysDecidesTheStep() {
        // Two approvals or two rejections, out of A, B and C.
        CompiledFlowNode node = step(ApprovalNodeConfig.builder()
                .approvalMode(VoteThresholdMode.MIN_COUNT).minCount(2)
                .rejectMode(VoteThresholdMode.MIN_COUNT).rejectMinCount(2).build());

        // All three there: one side or the other reaches two.
        assertEquals(2, resolution.resolveRequiredRejectCount(node, List.of("A", "B", "C")));
        // C dropped: A approves, B rejects — one rejection has to decide it.
        assertEquals(2, resolution.resolveRequiredApprovalCount(node, List.of("A", "B")));
        assertEquals(1, resolution.resolveRequiredRejectCount(node, List.of("A", "B")));
    }

    @Test
    void aStepEveryoneMustApproveIsRejectedByAnyOne() {
        CompiledFlowNode node = step(ApprovalNodeConfig.builder()
                .approvalMode(VoteThresholdMode.UNANIMOUS).rejectMode(VoteThresholdMode.UNANIMOUS).build());

        assertEquals(3, resolution.resolveRequiredApprovalCount(node, List.of("A", "B", "C")));
        assertEquals(1, resolution.resolveRequiredRejectCount(node, List.of("A", "B", "C")));
    }

    @Test
    void anyOneApprovingAndEveryoneRejectingStaysAsConfigured() {
        CompiledFlowNode node = step(ApprovalNodeConfig.builder()
                .approvalMode(VoteThresholdMode.ANY_ONE).rejectMode(VoteThresholdMode.UNANIMOUS).build());

        assertEquals(1, resolution.resolveRequiredApprovalCount(node, List.of("A", "B", "C")));
        assertEquals(3, resolution.resolveRequiredRejectCount(node, List.of("A", "B", "C")));
    }

    @Test
    void mergingTwoApproversCapsBothThresholds() {
        PendingApproval pending = PendingApproval.builder()
                .approvers(new ArrayList<>(List.of("A", "B", "C")))
                .totalApproverCount(3)
                .requiredApprovalCount(2)
                .requiredRejectCount(2)
                .approvedActors(new ArrayList<>())
                .rejectedActors(new ArrayList<>())
                .build();

        // C's place goes to B, already an approver of the step: two people left.
        new ApprovalLifecycleService(null).mergeApprover(pending, "C", "B");

        assertEquals(List.of("A", "B"), pending.getApprovers());
        assertEquals(2, pending.getRequiredApprovalCount());
        assertEquals(1, pending.getRequiredRejectCount());
    }
}
