package io.softa.starter.flow.compiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A step's field permissions name a permission for every field they list: a row saved before its
 * permission was picked validated and published as no rule at all, and the designer believed the field set.
 */
class ApprovalFieldPermissionValidationTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void aFieldListedWithoutAPermissionIsRefused() {
        Map<String, Object> permissions = new HashMap<>();
        permissions.put("businessPurpose", "");
        permissions.put("claimName", null);
        permissions.put("totalAmount", "Readonly");

        List<CompileDiagnostic> diagnostics = compiler.validate(flowWithPermissions(permissions));
        List<CompileDiagnostic> missing = diagnostics.stream()
                .filter(d -> "MISSING_FIELD_PERMISSION".equals(d.code()))
                .toList();

        assertThat(missing).as(diagnostics::toString).hasSize(2).allSatisfy(d -> {
            assertThat(d.nodeId()).isEqualTo("approval");
            assertThat(d.field()).isEqualTo("config.formPermissions");
            assertThat(d.severity()).isEqualTo(CompileDiagnostic.Severity.ERROR);
        });
        assertThat(missing).extracting(CompileDiagnostic::message)
                .anySatisfy(message -> assertThat(message).contains("'businessPurpose'"))
                .anySatisfy(message -> assertThat(message).contains("'claimName'"));
        // Said as what it is — a permission not picked — not as a config that cannot be read; and the rest of the
        // step is still checked.
        assertThat(diagnostics).noneMatch(d -> "UNREADABLE_CONFIG".equals(d.code()));
        assertThat(diagnostics).hasSize(2);
    }

    @Test
    void everyFieldWithItsPermissionPasses() {
        List<CompileDiagnostic> diagnostics = compiler.validate(flowWithPermissions(Map.of(
                "businessPurpose", "Hidden", "claimName", "Editable", "startDate", "Required", "totalAmount", "Readonly")));

        assertThat(diagnostics).isEmpty();
    }

    private static DesignFlowDefinition flowWithPermissions(Map<String, Object> formPermissions) {
        return DesignFlowDefinition.builder()
                .name("Field Permission Test")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("ExpenseClaim"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of("2001"))), "formPermissions", formPermissions))
                                        .build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }
}
