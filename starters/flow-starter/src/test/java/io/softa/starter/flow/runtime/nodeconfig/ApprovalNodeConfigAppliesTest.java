package io.softa.starter.flow.runtime.nodeconfig;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of a step's settings apply, by its groups of approvers: the approval mode where it may have several approvers,
 * the empty-approver strategy where a group finds its people by rule.
 */
class ApprovalNodeConfigAppliesTest {

    private static final Map<String, Object> ANN = Map.of("type", "Users", "userIds", List.of("1001"));
    private static final Map<String, Object> ANN_AND_BOB = Map.of("type", "Users", "userIds", List.of("1001", "1002"));
    private static final Map<String, Object> SUPERVISOR = Map.of("type", "Supervisor", "level", 1);
    private static final Map<String, Object> HR_ROLE = Map.of("type", "RoleQuery", "roleCodes", List.of("HR"));

    private static ApprovalNodeConfig step(Map<String, Object>... groups) {
        return ApprovalNodeConfig.builder().approverSources(List.of(groups)).build();
    }

    @Test
    void theModeAppliesWhereTheStepMayHaveSeveralApprovers() {
        assertFalse(step(ANN).mayHaveSeveralApprovers(), "one named person");
        assertFalse(step(Map.of("type", "Users", "userIds", List.of("1001", " ", "1001"))).mayHaveSeveralApprovers(),
                "one person, named twice");
        assertFalse(step(SUPERVISOR).mayHaveSeveralApprovers(), "a supervisor is one person");
        assertFalse(step(Map.of("type", "DeptLeader")).mayHaveSeveralApprovers(), "a department's leader");
        assertFalse(step().mayHaveSeveralApprovers(), "no group");

        assertTrue(step(ANN_AND_BOB).mayHaveSeveralApprovers(), "two named people");
        assertTrue(step(HR_ROLE).mayHaveSeveralApprovers(), "a role may have several holders");
        assertTrue(step(ANN, SUPERVISOR).mayHaveSeveralApprovers(), "two groups");
        assertTrue(step(Map.of("type", "Nobody knows")).mayHaveSeveralApprovers(), "a source this version does not know");
    }

    @Test
    void theStrategyAppliesWhereAGroupFindsItsPeopleByRule() {
        assertFalse(step(ANN).findsApproversByRule());
        assertFalse(step(ANN, ANN_AND_BOB).findsApproversByRule(), "every group names its people");
        assertFalse(step(Map.of("level", 1)).findsApproversByRule(), "a group with no source finds nobody by rule");

        assertTrue(step(SUPERVISOR).findsApproversByRule());
        assertTrue(step(ANN, HR_ROLE).findsApproversByRule(), "one group of two finds its people");
    }
}
