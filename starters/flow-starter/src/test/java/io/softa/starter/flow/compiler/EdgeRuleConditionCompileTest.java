package io.softa.starter.flow.compiler;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.ChangeEvent;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.context.RecordModelFixture;

import static io.softa.starter.flow.runtime.context.RecordModelFixture.MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rule condition on an edge is checked against the fields of the record the flow starts on. */
class EdgeRuleConditionCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = RecordModelFixture.mockModelManager();
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aRuleEdgeBesideTheDefaultEdgeCompilesAndKeepsItsRule() {
        Filters rule = Filters.of(List.of(List.of("days", ">", 3), "AND", List.of("leaveType", "=", "Annual")));

        CompiledFlowDefinition compiled = compiler.compile(approval(rule, null));

        assertEquals(rule.toString(), compiled.getTransitionIndex().get("long").getCondition().toString());
        assertTrue(compiled.getTransitionIndex().get("long").hasCondition());
        assertFalse(compiled.getTransitionIndex().get("short").hasCondition());
    }

    @Test
    void anEdgeCannotHaveBothARuleAndAnExpression() {
        List<CompileDiagnostic> diagnostics = compiler.validate(approval(Filters.of(List.of("days", ">", 3)), "days > 3"));

        assertTrue(hasDiagnostic(diagnostics, "CONFLICTING_EDGE_CONDITIONS", "keep one"));
    }

    @Test
    void aRuleNeedsARecordToRead() {
        DesignFlowDefinition definition = flow(FlowScenario.AUTOMATION, new ApiTrigger(null),
                Filters.of(List.of("days", ">", 3)), null);

        assertTrue(hasDiagnostic(compiler.validate(definition), "RULE_WITHOUT_RECORD", "write an expression"));
    }

    @Test
    void anApprovalsRuleReadsOnlyTheFieldsItsApprovalsRouteOn() {
        List<CompileDiagnostic> diagnostics = compiler.validate(approval(
                Filters.of(List.of("createdTime", ">", "2026-01-01")), null));

        assertTrue(hasDiagnostic(diagnostics, "INVALID_RULE_CONDITION", "route on"));
        // A sensitive field is never routed, so a rule cannot read it either.
        assertTrue(hasDiagnostic(compiler.validate(approval(Filters.of(List.of("bankAccount", "=", "x")), null)),
                "INVALID_RULE_CONDITION", "declared sensitive"));
    }

    @Test
    void aRuleIsCheckedLikeAFieldCondition() {
        assertTrue(hasDiagnostic(compiler.validate(approval(Filters.of(List.of("nights", ">", 3)), null)),
                "INVALID_RULE_CONDITION", "does not exist"));
        assertTrue(hasDiagnostic(compiler.validate(approval(Filters.of(List.of("leaveType", ">", "A")), null)),
                "INVALID_RULE_CONDITION", "has no order"));
        assertTrue(hasDiagnostic(compiler.validate(approval(Filters.of(List.of("departmentId", "PARENT OF", List.of("7"))), null)),
                "INVALID_RULE_CONDITION", "needs a query"));
    }

    @Test
    void aRuleReadsOneHopThroughARelationToAFieldTheRelatedRecordWouldRouteOn() {
        Filters rule = Filters.of(List.of(List.of("departmentId.costCenter", "=", "CC1"), "AND",
                List.of("departmentId.level", ">=", 4)));

        assertTrue(compiler.validate(approval(rule, null)).stream().noneMatch(d -> "long".equals(d.edgeId())));
        // What a start reads from the related record, beside the record's own fields.
        assertEquals(Set.of("departmentId.costCenter", "departmentId.level"),
                compiler.compile(approval(rule, null)).rulePaths());
        // An entity change's edges read through a relation of the changed record as well.
        DesignFlowDefinition onChange = flow(FlowScenario.AUTOMATION,
                new EntityChangeTrigger(MODEL, List.of(ChangeEvent.UPDATE), null, null), rule, null);
        assertTrue(compiler.validate(onChange).stream().noneMatch(d -> "long".equals(d.edgeId())));
    }

    @Test
    void aPathEndsOnAFieldARouteMayReadAtMostTwoHopsOn() {
        // Two hops: through the department, then its parent.
        assertTrue(compiler.validate(approval(Filters.of(List.of("departmentId.parentId.costCenter", "=", "CC1")), null))
                .stream().noneMatch(d -> "long".equals(d.edgeId())));
        // A sensitive field on the way is no more routed than on the record itself; and two hops are all.
        assertTrue(hasDiagnostic(compiler.validate(approval(Filters.of(List.of("departmentId.budget", ">", 1)), null)),
                "INVALID_RULE_CONDITION", "is not a path a rule can read"));
        assertTrue(hasDiagnostic(compiler.validate(approval(
                        Filters.of(List.of("departmentId.parentId.parentId.costCenter", "=", "CC1")), null)),
                "INVALID_RULE_CONDITION", "is not a path a rule can read: up to 2 hops"));
        // The field it reaches is checked as the record's own are: a relation has no order.
        assertTrue(hasDiagnostic(compiler.validate(approval(Filters.of(List.of("departmentId.parentId", ">", "7")), null)),
                "INVALID_RULE_CONDITION", "has no order"));
    }

    private static boolean hasDiagnostic(List<CompileDiagnostic> diagnostics, String code, String text) {
        return diagnostics.stream().anyMatch(d -> code.equals(d.code()) && "long".equals(d.edgeId())
                && d.message().contains(text));
    }

    private static DesignFlowDefinition approval(Filters rule, String expression) {
        return flow(FlowScenario.APPROVAL, new SubmitTrigger(MODEL), rule, expression);
    }

    /** start → long (the condition) → endLong; start → short (default) → endShort. */
    private static DesignFlowDefinition flow(FlowScenario scenario, TriggerSource trigger, Filters rule,
                                             String expression) {
        return DesignFlowDefinition.builder()
                .name("Leave Routing")
                .scenario(scenario)
                .trigger(trigger)
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(node("start", FlowNodeType.START), node("endLong", FlowNodeType.END),
                                node("endShort", FlowNodeType.END)))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("long").source("start").target("endLong")
                                        .condition(rule).conditionExpression(expression).build(),
                                FlowGraphEdge.builder().id("short").source("start").target("endShort").build()))
                        .build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }
}
