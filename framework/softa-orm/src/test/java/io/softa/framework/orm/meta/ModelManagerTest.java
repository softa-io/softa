package io.softa.framework.orm.meta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.enums.FieldType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Slf4j
class ModelManagerTest {

    @BeforeAll
    static void ensureSystemConfig() {
        // Constructing IllegalArgumentException reaches I18n.get → ContextHolder.getContext,
        // which dereferences SystemConfig.env. In a raw unit test context env is null;
        // the framework's own auto-config is what populates it in production.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    private static MetaField field(String modelName, String fieldName, FieldType type, String relatedModel) {
        MetaField metaField = new MetaField();
        metaField.setModelName(modelName);
        metaField.setFieldName(fieldName);
        metaField.setFieldType(type);
        metaField.setRelatedModel(relatedModel);
        return metaField;
    }

    private static MetaField storedField(String modelName, String fieldName, FieldType type, String relatedModel) {
        MetaField metaField = field(modelName, fieldName, type, relatedModel);
        metaField.setDynamic(false);
        return metaField;
    }

    private static MetaField dynamicField(String modelName, String fieldName, FieldType type) {
        MetaField metaField = field(modelName, fieldName, type, null);
        metaField.setDynamic(true);
        return metaField;
    }

    @Test
    void initComputedFields() {
        String formula = "if seq != \"6\" { \"17\" } else { \"99\" }";
        List<String> dependentFields = ComputeUtils.getVariables(formula);
        Assertions.assertNotNull(dependentFields);
        Map<String, Object> env = new HashMap<>();
        env.put("seq", "5");
        Object result = ComputeUtils.execute(formula, env);
        log.info(result.toString());
    }

    @Test
    void getLastFieldOfCascadedReturnsStoredLeaf() {
        MetaField rel = storedField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField leaf = storedField("DesignDeployment", "deployStatus", FieldType.OPTION, null);
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            mock.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mock.when(() -> ModelManager.existField("DesignDeployment", "deployStatus")).thenReturn(true);
            mock.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mock.when(() -> ModelManager.getModelField("DesignDeployment", "deployStatus")).thenReturn(leaf);

            MetaField result = ModelManager.getLastFieldOfCascaded("AppEnv", "lastDeploymentId.deployStatus");

            assertSame(leaf, result);
        }
    }

    @Test
    void getLastFieldOfCascadedRejectsDynamicLeaf() {
        MetaField rel = storedField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField leaf = dynamicField("DesignDeployment", "computedThing", FieldType.STRING);
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            mock.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mock.when(() -> ModelManager.existField("DesignDeployment", "computedThing")).thenReturn(true);
            mock.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mock.when(() -> ModelManager.getModelField("DesignDeployment", "computedThing")).thenReturn(leaf);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> ModelManager.getLastFieldOfCascaded("AppEnv", "lastDeploymentId.computedThing"));
            // Original error wording preserved so external callers can still pattern-match if needed.
            assertEquals(true, ex.getMessage().contains("must be a stored field"));
        }
    }

    @Test
    void getLastFieldOfCascadedRejectsTraverseThroughOneToMany() {
        MetaField team = storedField("AppEnv", "team", FieldType.ONE_TO_MANY, "Member");
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            mock.when(() -> ModelManager.existField("AppEnv", "team")).thenReturn(true);
            mock.when(() -> ModelManager.getModelField("AppEnv", "team")).thenReturn(team);

            assertThrows(IllegalArgumentException.class,
                    () -> ModelManager.getLastFieldOfCascaded("AppEnv", "team.assigneeId"));
        }
    }

    @Test
    void getLastFieldOfCascadedRejectsMissingField() {
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            mock.when(() -> ModelManager.existField("AppEnv", "ghost")).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> ModelManager.getLastFieldOfCascaded("AppEnv", "ghost.something"));
        }
    }

    @Test
    void getLastFieldOfCascadedRejectsExcessiveDepth() {
        // CASCADE_LEVEL = 4, so a path with 6 segments (5 hops) is too deep.
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ModelManager.getLastFieldOfCascaded("AppEnv", "a.b.c.d.e.f"));
            // No metadata calls should have happened — depth check fires first.
            mock.verify(() -> ModelManager.existField(Mockito.anyString(), Mockito.anyString()), Mockito.never());
        }
    }
}
