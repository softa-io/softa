package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** Specific people A, B and C: a step that may have several approvers, so its mode applies. */
    private static final List<Map<String, Object>> A_B_C = List.of(Map.of("type", "Users", "userIds", List.of("A", "B", "C")));

    private static CompiledFlowNode step(ApprovalNodeConfig config) {
        return CompiledFlowNode.builder().nodeId("n1").type(FlowNodeType.APPROVAL).parsedConfig(config).build();
    }

    @Test
    void aStepAskingMorePeopleThanItHasLeftAsksThoseItHas() {
        // "Two of A and B": B left, and was dropped as the step started.
        CompiledFlowNode node = step(ApprovalNodeConfig.builder().approverSources(A_B_C)
                .approvalMode(VoteThresholdMode.MIN_COUNT).minCount(2).build());

        assertEquals(1, resolution.resolveRequiredApprovalCount(node, List.of("A")));
        assertEquals(2, resolution.resolveRequiredApprovalCount(node, List.of("A", "B")));
    }

    @Test
    void aStepEveryoneMustApproveCountsEveryone() {
        CompiledFlowNode node = step(ApprovalNodeConfig.builder().approverSources(A_B_C)
                .approvalMode(VoteThresholdMode.UNANIMOUS).build());

        assertEquals(3, resolution.resolveRequiredApprovalCount(node, List.of("A", "B", "C")));
    }

    /**
     * A step with one approver at most — one named person, a supervisor — passes on that one's approval: its mode does
     * not apply, whatever it holds, even one that could not be counted ("a percentage" with none set).
     */
    @Test
    void aStepWithOneApproverAtMostPassesOnTheirApprovalWhateverItsMode() {
        for (Map<String, Object> group : List.<Map<String, Object>>of(
                Map.of("type", "Users", "userIds", List.of("A")),
                Map.of("type", "Supervisor", "level", 2),
                Map.of("type", "DeptHrbp"))) {
            CompiledFlowNode node = step(ApprovalNodeConfig.builder().approverSources(List.of(group))
                    .approvalMode(VoteThresholdMode.PERCENTAGE).build());

            assertEquals(VoteThresholdMode.ANY_ONE, resolution.resolveApprovalMode(node), group.toString());
            assertEquals(1, resolution.resolveRequiredApprovalCount(node, List.of("A")), group.toString());
        }
        CompiledFlowNode roles = step(ApprovalNodeConfig.builder()
                .approverSources(List.of(Map.of("type", "RoleQuery", "roleCodes", List.of("HR"))))
                .approvalMode(VoteThresholdMode.UNANIMOUS).build());
        assertEquals(VoteThresholdMode.UNANIMOUS, resolution.resolveApprovalMode(roles), "a role may have several holders");
    }

    @Test
    void mergingTwoApproversCapsTheApprovalsTheStepNeeds() {
        PendingApproval pending = PendingApproval.builder()
                .approvers(new ArrayList<>(List.of("A", "B", "C")))
                .requiredApprovalCount(3)
                .approvedActors(new ArrayList<>())
                .build();

        // C's place goes to B, already an approver of the step: two people left.
        new ApprovalLifecycleService().mergeApprover(pending, "C", "B");

        assertEquals(List.of("A", "B"), pending.getApprovers());
        assertEquals(2, pending.getRequiredApprovalCount());
    }
}
