package io.softa.starter.flow.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.flow.dto.FlowDelegationFlowView;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.TestActorAccounts;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.service.FlowDesignService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * The flows a delegation rule can be scoped to are every enabled approval flow of the tenant with an effective
 * revision, with its approval steps, by name; a rule names one of them — and a delegate who can act.
 */
class FlowDelegationFlowsTest {

    private final FlowDelegationServiceImpl service = spy(new FlowDelegationServiceImpl());
    private final FlowBundleRegistry registry = mock(FlowBundleRegistry.class);
    private final FlowDesignService designs = mock(FlowDesignService.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<FlowBundleRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);
        ObjectProvider<FlowDesignService> designProvider = mock(ObjectProvider.class);
        when(designProvider.getIfAvailable()).thenReturn(designs);
        ReflectionTestUtils.setField(service, "bundleRegistry", registryProvider);
        ReflectionTestUtils.setField(service, "designService", designProvider);
        // Bob's account is frozen.
        ReflectionTestUtils.setField(service, "actorAccounts",
                TestActorAccounts.of(Map.of(2002L, "Frozen"), Map.of("2002", "Bob Wu")));

        // The active revisions of the tenant's enabled flows: two approval flows and an automation.
        when(registry.list()).thenReturn(List.of(
                flow("leave", "Leave (old name)", FlowScenario.APPROVAL, List.of("start", "manager", "notify", "hr", "end")),
                flow("sync", "Sync", FlowScenario.AUTOMATION, List.of("start", "end")),
                flow("claim", "Expense claim", FlowScenario.APPROVAL, List.of("start", "finance", "end"))));
        FlowDesign leave = new FlowDesign();
        leave.setId("leave");
        leave.setFlowName("Leave");
        when(designs.getByIds(anyList(), anyCollection())).thenReturn(List.of(leave));
        doReturn(9L).when(service).createOne(any(FlowDelegation.class));
    }

    @Test
    void everyApprovalFlowInUseWithItsApprovalStepsByName() {
        List<FlowDelegationFlowView> flows = service.listDelegableFlows();

        assertEquals(List.of(
                new FlowDelegationFlowView("claim", "Expense claim",
                        List.of(new FlowDelegationFlowView.Node("finance", "Finance"))),
                new FlowDelegationFlowView("leave", "Leave", List.of(
                        new FlowDelegationFlowView.Node("manager", "Manager"),
                        new FlowDelegationFlowView.Node("hr", "Hr")))), flows,
                "named as the flow is called now, its steps in flow order, the other nodes left out");
    }

    @Test
    void aRuleOfOneFlowOrStepNamesOneInUse() {
        assertDoesNotThrow(() -> service.createDelegation(rule("Node", "leave", "hr")));
        assertDoesNotThrow(() -> service.createDelegation(rule("Flow", "claim", null)));

        assertEquals("Flow sync is not an approval flow in use",
                assertThrows(FlowActionValidationException.class,
                        () -> service.createDelegation(rule("Flow", "sync", null))).getMessage());
        assertEquals("Flow 'Leave' has no approval step notify",
                assertThrows(FlowActionValidationException.class,
                        () -> service.createDelegation(rule("Node", "leave", "notify"))).getMessage());
        assertThrows(FlowActionValidationException.class, () -> service.createDelegation(rule("Node", "leave", null)));
        verify(service, times(2)).createOne(any(FlowDelegation.class));
    }

    @Test
    void aDelegateWhoCannotActIsRefused() {
        FlowDelegation rule = rule("All", null, null);
        rule.setDelegateId("2002");

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.createDelegation(rule));

        assertEquals("Bob Wu's account is frozen.", refused.getMessage());
        verify(service, never()).createOne(any(FlowDelegation.class));
    }

    private static FlowDelegation rule(String scope, String flowId, String nodeId) {
        FlowDelegation delegation = new FlowDelegation();
        delegation.setDelegatorId("2001");
        delegation.setDelegateId("2003");
        delegation.setScope(scope);
        delegation.setFlowId(flowId);
        delegation.setNodeId(nodeId);
        return delegation;
    }

    /** An active revision whose nodes run in {@code order}: start and end as such, the rest approval steps — but notify. */
    private static CompiledFlowDefinition flow(String flowId, String name, FlowScenario scenario, List<String> order) {
        Map<String, CompiledFlowNode> nodes = new LinkedHashMap<>();
        // Indexed out of flow order: the steps come in the order the flow runs them.
        order.reversed().forEach(nodeId -> nodes.put(nodeId, CompiledFlowNode.builder()
                .nodeId(nodeId)
                .type(switch (nodeId) {
                    case "start" -> FlowNodeType.START;
                    case "end" -> FlowNodeType.END;
                    case "notify" -> FlowNodeType.SEND_INBOX_NOTIFICATION;
                    default -> FlowNodeType.APPROVAL;
                })
                .label(Character.toUpperCase(nodeId.charAt(0)) + nodeId.substring(1))
                .build()));
        return CompiledFlowDefinition.builder()
                .flowId(flowId).flowName(name).scenario(scenario).nodeIndex(nodes).topologicalOrder(order).build();
    }
}
