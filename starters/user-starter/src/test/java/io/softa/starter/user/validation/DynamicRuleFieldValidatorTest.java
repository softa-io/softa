package io.softa.starter.user.validation;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.user.service.DynamicRoleSyncJob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A role's membership rule is matched across the whole tenant, so a rule testing a field its writer cannot see on
 * every employee would sort employees by a value the writer is never shown. The wizard refused such a rule, but the
 * generic create and update endpoints wrote it unchecked and the nightly sync granted the role by it: the check is a
 * write validator now, on every write of a Role.
 */
class DynamicRuleFieldValidatorTest {

    private final DynamicRoleSyncJob job = mock(DynamicRoleSyncJob.class);
    private final DynamicRuleFieldValidator validator = new DynamicRuleFieldValidator();
    private MockedStatic<ModelManager> models;

    private static final String SENSITIVE_RULE = "[\"basePay\", \">\", 5000]";
    private static final String PLAIN_RULE = "[\"departmentId\", \"=\", 9]";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(validator, "dynamicRoleSyncJob", job);
        when(job.ruleFieldsNotSeenByCaller(any())).thenAnswer(inv -> {
            JsonNode rule = inv.getArgument(0);
            return rule.toString().contains("basePay") ? new LinkedHashSet<>(List.of("basePay")) : Set.of();
        });
        models = Mockito.mockStatic(ModelManager.class);
        MetaField basePay = new MetaField();
        ReflectionTestUtils.setField(basePay, "fieldName", "basePay");
        ReflectionTestUtils.setField(basePay, "label", "Base Pay");
        models.when(() -> ModelManager.getModelFieldOrNull(eq("Employee"), eq("basePay"))).thenReturn(basePay);
        models.when(() -> ModelManager.getModelFieldOrNull(anyString(), eq("departmentId"))).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private static Map<String, Object> role(Object rule) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("name", "High earners");
        row.put("dynamicFilter", rule);
        return row;
    }

    @Test
    void appliesToRolesOnly() {
        assertThat(validator.supports("Role")).isTrue();
        assertThat(validator.supports("Employee")).isFalse();
    }

    @Test
    void aNewRuleTestingAFieldTheWriterCannotSeeEverywhereIsRefusedNamingIt() {
        WriteContext ctx = WriteContext.of("Role", AccessType.CREATE, role(JsonUtils.stringToObject(SENSITIVE_RULE, JsonNode.class)), null);

        validator.validateCreate(ctx);

        assertThat(ctx.errors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("dynamicFilter");
            assertThat(error.message()).isEqualTo("The membership rule tests Base Pay, which you cannot see on every employee.");
        });
    }

    @Test
    void aRuleSentAsTextOrAsPlainListsIsJudgedTheSame() {
        WriteContext asText = WriteContext.of("Role", AccessType.CREATE, role(SENSITIVE_RULE), null);
        WriteContext asList = WriteContext.of("Role", AccessType.CREATE, role(List.of("basePay", ">", 5000)), null);

        validator.validateCreate(asText);
        validator.validateCreate(asList);

        assertThat(asText.hasErrors()).isTrue();
        assertThat(asList.hasErrors()).isTrue();
    }

    @Test
    void aRuleOnFieldsTheWriterSeesEverywherePasses() {
        WriteContext ctx = WriteContext.of("Role", AccessType.CREATE, role(PLAIN_RULE), null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void anUpdateThroughAGenericEndpointThatChangesTheRuleIsJudged() {
        Map<String, Object> patch = new HashMap<>(Map.of("id", 7L, "dynamicFilter", SENSITIVE_RULE));
        WriteContext ctx = WriteContext.of("Role", AccessType.UPDATE, role(SENSITIVE_RULE), patch, role(PLAIN_RULE));

        validator.validateUpdate(ctx);

        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void anUpdateLeavingTheRuleAsItWasPassesThoughTheWriterCouldNotHaveWrittenIt() {
        // Stored as text, sent back as a tree: the same rule.
        Map<String, Object> patch = new HashMap<>(Map.of("id", 7L, "dynamicFilter",
                JsonUtils.stringToObject(SENSITIVE_RULE, JsonNode.class)));
        WriteContext ctx = WriteContext.of("Role", AccessType.UPDATE, role(SENSITIVE_RULE), patch, role(SENSITIVE_RULE));

        validator.validateUpdate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
        verify(job, never()).ruleFieldsNotSeenByCaller(any());
    }

    @Test
    void anUpdateNotTouchingTheRuleOrClearingItPasses() {
        Map<String, Object> rename = new HashMap<>(Map.of("id", 7L, "name", "Renamed"));
        WriteContext renamed = WriteContext.of("Role", AccessType.UPDATE, role(SENSITIVE_RULE), rename, role(SENSITIVE_RULE));
        Map<String, Object> clear = new HashMap<>();
        clear.put("id", 7L);
        clear.put("dynamicFilter", null);
        WriteContext cleared = WriteContext.of("Role", AccessType.UPDATE, role(null), clear, role(SENSITIVE_RULE));

        validator.validateUpdate(renamed);
        validator.validateUpdate(cleared);

        assertThat(renamed.hasErrors()).isFalse();
        assertThat(cleared.hasErrors()).isFalse();
    }
}
