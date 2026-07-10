package io.softa.starter.user.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeRuleTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

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
