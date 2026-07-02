package io.softa.starter.user.dto;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeRuleTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void extractPrincipalRefs_nullExpr_returnsEmpty() {
        ScopeRule rule = new ScopeRule();
        assertThat(rule.extractPrincipalRefs()).isEmpty();
    }

    @Test
    void extractPrincipalRefs_noRefsInside_returnsEmpty() {
        ArrayNode arr = JSON.arrayNode();
        arr.add("createdId").add("=").add(42L);
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(arr);
        assertThat(rule.extractPrincipalRefs()).isEmpty();
    }

    @Test
    void extractPrincipalRefs_singleLeafRef() {
        ArrayNode arr = JSON.arrayNode();
        arr.add("createdId").add("=").add("$principal.userId");
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(arr);
        assertThat(rule.extractPrincipalRefs()).containsExactly("userId");
    }

    @Test
    void extractPrincipalRefs_multipleRefsAcrossOrComposite() {
        ArrayNode arr = JSON.arrayNode();
        ArrayNode left = JSON.arrayNode();
        left.add("createdId").add("=").add("$principal.userId");
        ArrayNode right = JSON.arrayNode();
        right.add("assigneeId").add("=").add("$principal.employeeId");
        arr.add(left).add("OR").add(right);
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(arr);
        assertThat(rule.extractPrincipalRefs())
                .containsExactlyInAnyOrder("userId", "employeeId");
    }

    @Test
    void extractPrincipalRefs_ignoresNonPrincipalStrings() {
        ArrayNode arr = JSON.arrayNode();
        arr.add("createdBy").add("=").add("$other.userId");
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(arr);
        assertThat(rule.extractPrincipalRefs()).isEmpty();
    }

    @Test
    void extractPrincipalRefs_walksNestedObject() {
        ObjectNode obj = JSON.objectNode();
        obj.put("target", "$principal.departmentId");
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(obj);
        assertThat(rule.extractPrincipalRefs()).containsExactly("departmentId");
    }

    @Test
    void scopeExprField_objectExpr_returnsChild() {
        ObjectNode obj = JSON.objectNode();
        obj.put("deptId", "dept-42");
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(obj);
        JsonNode field = rule.scopeExprField("deptId");
        assertThat(field).isNotNull();
        assertThat(field.asString()).isEqualTo("dept-42");
    }

    @Test
    void scopeExprField_nullExpr_returnsNull() {
        assertThat(new ScopeRule().scopeExprField("anything")).isNull();
    }

    @Test
    void scopeExprField_arrayExpr_returnsNull() {
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(JSON.arrayNode());
        assertThat(rule.scopeExprField("anything")).isNull();
    }

    @Test
    void scopeExprField_missingField_returnsNull() {
        ObjectNode obj = JSON.objectNode();
        obj.put("deptId", "dept-42");
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(obj);
        assertThat(rule.scopeExprField("legalEntityId")).isNull();
    }
}
