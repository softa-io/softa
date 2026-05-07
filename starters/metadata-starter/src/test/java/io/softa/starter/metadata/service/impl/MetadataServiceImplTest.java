package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.CascadeFieldWalker;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.metadata.controller.dto.MetaFieldDTO;
import io.softa.starter.metadata.controller.dto.MetaModelDTO;
import io.softa.starter.metadata.controller.dto.PathResolution;
import io.softa.starter.metadata.controller.dto.ResolveCascadedPathsResponse;
import io.softa.starter.metadata.dto.MetadataUpgradePackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MetadataServiceImplTest {

    @BeforeAll
    static void ensureSystemConfig() {
        // IllegalArgumentException construction reaches I18n via BaseException, which
        // requires SystemConfig.env to be non-null. In production it's populated by
        // Spring auto-config; raw unit tests must seed it explicitly.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @Test
    void exportRuntimeMetadataScopesMainModelByAppId() {
        MetadataServiceImpl service = new MetadataServiceImpl();
        @SuppressWarnings("unchecked")
        ModelService<Serializable> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);

        List<Map<String, Object>> rows = List.of(Map.of("id", 1L, "fieldName", "name", "appId", 42L));
        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class))).thenReturn(rows);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SysField", "appId")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany("SysField"))
                    .thenReturn(Set.of("id", "fieldName", "appId"));

            List<Map<String, Object>> result = service.exportRuntimeMetadata("SysField", 42L);

            assertEquals(rows, result);
            verify(modelService).searchList(eq("SysField"), Mockito.argThat(query ->
                    query != null
                            && query.getFields().containsAll(Set.of("id", "fieldName", "appId"))
                            && query.getFilters() != null
                            && query.getFilters().toString().contains("appId")));
        }
    }

    @Test
    void exportRuntimeMetadataResolvesTransModelViaParentIds() {
        MetadataServiceImpl service = new MetadataServiceImpl();
        @SuppressWarnings("unchecked")
        ModelService<Serializable> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);

        List<Map<String, Object>> parentRows = List.of(Map.of("id", 7L), Map.of("id", 8L));
        List<Map<String, Object>> transRows = List.of(Map.of("id", 100L, "rowId", 7L, "languageCode", "en"));
        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class))).thenReturn(parentRows);
        when(modelService.searchList(eq("SysFieldTrans"), Mockito.any(FlexQuery.class))).thenReturn(transRows);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SysFieldTrans", "appId")).thenReturn(false);
            modelManager.when(() -> ModelManager.existField("SysField", "appId")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany("SysFieldTrans"))
                    .thenReturn(Set.of("id", "rowId", "languageCode"));

            List<Map<String, Object>> result = service.exportRuntimeMetadata("SysFieldTrans", 42L);

            assertEquals(transRows, result);
            verify(modelService).searchList(eq("SysField"), Mockito.argThat(query ->
                    query != null
                            && query.getFields().contains(ModelConstant.ID)
                            && query.getFilters() != null
                            && query.getFilters().toString().contains("appId")));
            verify(modelService).searchList(eq("SysFieldTrans"), Mockito.argThat(query ->
                    query != null
                            && query.getFilters() != null
                            && query.getFilters().toString().contains("rowId")));
        }
    }

    @Test
    void exportRuntimeMetadataReturnsEmptyWhenTransParentHasNoRows() {
        MetadataServiceImpl service = new MetadataServiceImpl();
        @SuppressWarnings("unchecked")
        ModelService<Serializable> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);

        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class))).thenReturn(List.of());

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SysFieldTrans", "appId")).thenReturn(false);
            modelManager.when(() -> ModelManager.existField("SysField", "appId")).thenReturn(true);

            List<Map<String, Object>> result = service.exportRuntimeMetadata("SysFieldTrans", 42L);

            assertEquals(List.of(), result);
            verify(modelService, never()).searchList(eq("SysFieldTrans"), Mockito.any(FlexQuery.class));
        }
    }

    @Test
    void exportRuntimeMetadataRejectsUnscopableModel() {
        MetadataServiceImpl service = new MetadataServiceImpl();
        @SuppressWarnings("unchecked")
        ModelService<Serializable> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SomeOtherModel", "appId")).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> service.exportRuntimeMetadata("SomeOtherModel", 42L));
            verify(modelService, never()).searchList(Mockito.anyString(), Mockito.any(FlexQuery.class));
        }
    }

    @Test
    void upgradeMetadataRequiresInsertIdWhenCreatingRows() {
        MetadataServiceImpl service = new MetadataServiceImpl();
        @SuppressWarnings("unchecked")
        ModelService<Serializable> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);

        MetadataUpgradePackage metadataPackage = new MetadataUpgradePackage();
        metadataPackage.setModelName("SysField");
        metadataPackage.setCreateRows(List.of(Map.of(ModelConstant.ID, 10L, "fieldName", "name")));

        SystemConfig previous = SystemConfig.env;
        SystemConfig config = new SystemConfig();
        config.setEnableInsertId(false);
        SystemConfig.env = config;
        try {
            assertThrows(IllegalArgumentException.class, () -> service.upgradeMetadata(List.of(metadataPackage)));
            verify(modelService, never()).createList(eq("SysField"), Mockito.anyList());
        } finally {
            SystemConfig.env = previous;
        }
    }

    @Test
    void upgradeMetadataValidatesCreatedIdsMatchRequestedIds() {
        MetadataServiceImpl service = new MetadataServiceImpl();
        @SuppressWarnings("unchecked")
        ModelService<Serializable> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);

        MetadataUpgradePackage metadataPackage = new MetadataUpgradePackage();
        metadataPackage.setModelName("SysField");
        metadataPackage.setCreateRows(List.of(Map.of(ModelConstant.ID, 10L, "fieldName", "name")));
        when(modelService.createList(eq("SysField"), Mockito.anyList())).thenReturn(List.of(99L));

        SystemConfig previous = SystemConfig.env;
        SystemConfig config = new SystemConfig();
        config.setEnableInsertId(true);
        SystemConfig.env = config;
        try {
            assertThrows(IllegalArgumentException.class, () -> service.upgradeMetadata(List.of(metadataPackage)));
        } finally {
            SystemConfig.env = previous;
        }
    }

    @Test
    void upgradeMetadataFailsWhenUpdateTargetsDoNotExist() {
        MetadataServiceImpl service = new MetadataServiceImpl();
        @SuppressWarnings("unchecked")
        ModelService<Serializable> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);

        MetadataUpgradePackage metadataPackage = new MetadataUpgradePackage();
        metadataPackage.setModelName("SysField");
        metadataPackage.setUpdateRows(List.of(
                Map.of(ModelConstant.ID, 10L, "fieldName", "name"),
                Map.of(ModelConstant.ID, 11L, "fieldName", "code")));
        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class)))
                .thenReturn(List.of(Map.of(ModelConstant.ID, 10L)));

        SystemConfig previous = SystemConfig.env;
        SystemConfig config = new SystemConfig();
        config.setEnableInsertId(true);
        SystemConfig.env = config;
        try {
            assertThrows(IllegalArgumentException.class, () -> service.upgradeMetadata(List.of(metadataPackage)));
            verify(modelService, never()).updateList(eq("SysField"), Mockito.anyList());
        } finally {
            SystemConfig.env = previous;
        }
    }

    // ───────── resolveCascadedPaths ─────────

    private static MetaField mockField(String modelName, String fieldName, FieldType type, String relatedModel) {
        MetaField metaField = mock(MetaField.class);
        when(metaField.getModelName()).thenReturn(modelName);
        when(metaField.getFieldName()).thenReturn(fieldName);
        when(metaField.getFieldType()).thenReturn(type);
        when(metaField.getRelatedModel()).thenReturn(relatedModel);
        return metaField;
    }

    /**
     * Stub the dto mapper so tests can assert on metaModels purely by the model names
     * passed in, without having to populate {@link io.softa.framework.orm.meta.MetaModel} getters.
     */
    private static void stubMapper(MockedStatic<MetadataDtoMapper> mapper) {
        mapper.when(() -> MetadataDtoMapper.toModelDTO(anyString())).thenAnswer(inv -> {
            MetaModelDTO dto = new MetaModelDTO();
            dto.setModelName(inv.getArgument(0));
            return dto;
        });
        mapper.when(() -> MetadataDtoMapper.toFieldDTO(any(MetaField.class))).thenAnswer(inv -> {
            MetaField f = inv.getArgument(0);
            MetaFieldDTO dto = new MetaFieldDTO();
            dto.setFieldName(f.getFieldName());
            dto.setModelName(f.getModelName());
            dto.setFieldType(f.getFieldType());
            return dto;
        });
    }

    private static MetadataServiceImpl serviceWithPermission(PermissionService permissionService) {
        MetadataServiceImpl service = new MetadataServiceImpl();
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        return service;
    }

    @Test
    void resolveCascadedPathsResolvesValidDepth2Path() {
        PermissionService permissionService = mock(PermissionService.class);
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        MetaField rel = mockField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField status = mockField("DesignDeployment", "deployStatus", FieldType.OPTION, null);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            stubMapper(mapper);
            mm.when(() -> ModelManager.validateModel("AppEnv")).thenAnswer(inv -> null);
            mm.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("DesignDeployment", "deployStatus")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mm.when(() -> ModelManager.getModelField("DesignDeployment", "deployStatus")).thenReturn(status);

            ResolveCascadedPathsResponse response = service.resolveCascadedPaths(
                    "AppEnv", List.of("lastDeploymentId.deployStatus"));

            // Root excluded — caller already has it.
            assertEquals(1, response.getMetaModels().size());
            assertEquals("DesignDeployment", response.getMetaModels().get(0).getModelName());
            assertEquals(1, response.getResolutions().size());
            PathResolution r = response.getResolutions().get(0);
            assertTrue(r.isOk());
            assertEquals("lastDeploymentId.deployStatus", r.getPath());
            assertNotNull(r.getMetaField());
            assertEquals("deployStatus", r.getMetaField().getFieldName());
        }
    }

    @Test
    void resolveCascadedPathsAllowsRelationLeafSegment() {
        PermissionService permissionService = mock(PermissionService.class);
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        MetaField rel = mockField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField owner = mockField("DesignDeployment", "ownerId", FieldType.MANY_TO_ONE, "Employee");

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            stubMapper(mapper);
            mm.when(() -> ModelManager.validateModel("AppEnv")).thenAnswer(inv -> null);
            mm.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("DesignDeployment", "ownerId")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mm.when(() -> ModelManager.getModelField("DesignDeployment", "ownerId")).thenReturn(owner);

            ResolveCascadedPathsResponse response = service.resolveCascadedPaths(
                    "AppEnv", List.of("lastDeploymentId.ownerId"));

            assertTrue(response.getResolutions().get(0).isOk());
        }
    }

    @Test
    void resolveCascadedPathsIsolatesFailedPaths() {
        PermissionService permissionService = mock(PermissionService.class);
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        MetaField rel = mockField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField status = mockField("DesignDeployment", "deployStatus", FieldType.OPTION, null);
        MetaField team = mockField("AppEnv", "team", FieldType.ONE_TO_MANY, "Member");

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            stubMapper(mapper);
            mm.when(() -> ModelManager.validateModel("AppEnv")).thenAnswer(inv -> null);
            mm.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("DesignDeployment", "deployStatus")).thenReturn(true);
            mm.when(() -> ModelManager.existField("AppEnv", "team")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mm.when(() -> ModelManager.getModelField("DesignDeployment", "deployStatus")).thenReturn(status);
            mm.when(() -> ModelManager.getModelField("AppEnv", "team")).thenReturn(team);

            ResolveCascadedPathsResponse response = service.resolveCascadedPaths(
                    "AppEnv", List.of(
                            "lastDeploymentId.deployStatus",
                            "team.assigneeId.email",
                            "lastDeploymentId.deployStatus"));

            // Closure: related models from successful paths only — root excluded, 'Member' excluded.
            List<String> modelNames = response.getMetaModels().stream()
                    .map(MetaModelDTO::getModelName).toList();
            assertEquals(List.of("DesignDeployment"), modelNames);
            assertFalse(modelNames.contains("Member"),
                    "Failed path must not pollute the metaModels closure");
            assertFalse(modelNames.contains("AppEnv"),
                    "Root model must not appear in the closure");

            assertEquals(3, response.getResolutions().size());
            assertTrue(response.getResolutions().get(0).isOk());
            assertFalse(response.getResolutions().get(1).isOk());
            assertEquals(CascadeFieldWalker.ErrorKind.TRAVERSE_TO_MANY,
                    response.getResolutions().get(1).getErrorCode());
            assertEquals(0, response.getResolutions().get(1).getErrorAt());
            assertTrue(response.getResolutions().get(2).isOk());
        }
    }

    @Test
    void resolveCascadedPathsPreservesRequestOrder() {
        PermissionService permissionService = mock(PermissionService.class);
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        MetaField rel = mockField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField status = mockField("DesignDeployment", "deployStatus", FieldType.OPTION, null);
        MetaField finished = mockField("DesignDeployment", "finishedTime", FieldType.DATE_TIME, null);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            stubMapper(mapper);
            mm.when(() -> ModelManager.validateModel("AppEnv")).thenAnswer(inv -> null);
            mm.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("DesignDeployment", "deployStatus")).thenReturn(true);
            mm.when(() -> ModelManager.existField("DesignDeployment", "finishedTime")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mm.when(() -> ModelManager.getModelField("DesignDeployment", "deployStatus")).thenReturn(status);
            mm.when(() -> ModelManager.getModelField("DesignDeployment", "finishedTime")).thenReturn(finished);

            List<String> requestPaths = List.of(
                    "lastDeploymentId.finishedTime",
                    "lastDeploymentId.deployStatus");
            ResolveCascadedPathsResponse response = service.resolveCascadedPaths("AppEnv", requestPaths);

            List<String> outputPaths = response.getResolutions().stream()
                    .map(PathResolution::getPath).toList();
            assertEquals(requestPaths, outputPaths);
        }
    }

    @Test
    void resolveCascadedPathsAccumulatesAccessFieldsForSuccessfulPathsOnly() {
        PermissionService permissionService = mock(PermissionService.class);
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        MetaField rel = mockField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField status = mockField("DesignDeployment", "deployStatus", FieldType.OPTION, null);
        MetaField team = mockField("AppEnv", "team", FieldType.ONE_TO_MANY, "Member");

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            stubMapper(mapper);
            mm.when(() -> ModelManager.validateModel("AppEnv")).thenAnswer(inv -> null);
            mm.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("DesignDeployment", "deployStatus")).thenReturn(true);
            mm.when(() -> ModelManager.existField("AppEnv", "team")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mm.when(() -> ModelManager.getModelField("DesignDeployment", "deployStatus")).thenReturn(status);
            mm.when(() -> ModelManager.getModelField("AppEnv", "team")).thenReturn(team);

            service.resolveCascadedPaths("AppEnv", List.of(
                    "lastDeploymentId.deployStatus",
                    "team.assigneeId.email"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Set<String>>> captor = ArgumentCaptor.forClass(Map.class);
            verify(permissionService).checkModelCascadeFieldsAccess(
                    eq("AppEnv"), captor.capture(), eq(AccessType.READ));

            Map<String, Set<String>> accessFields = captor.getValue();
            // Successful path → both segments tracked.
            assertEquals(Set.of("lastDeploymentId"), accessFields.get("AppEnv"));
            assertEquals(Set.of("deployStatus"), accessFields.get("DesignDeployment"));
            // Failed path's first segment (team) must NOT pollute access fields.
            assertFalse(accessFields.getOrDefault("AppEnv", new HashSet<>()).contains("team"),
                    "Failed path's segments must not appear in the access-fields map");
        }
    }

    @Test
    void resolveCascadedPathsRejectsBlankRootModel() {
        MetadataServiceImpl service = serviceWithPermission(mock(PermissionService.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveCascadedPaths("", List.of("foo.bar")));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveCascadedPaths(null, List.of("foo.bar")));
    }

    @Test
    void resolveCascadedPathsRejectsEmptyPaths() {
        MetadataServiceImpl service = serviceWithPermission(mock(PermissionService.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveCascadedPaths("AppEnv", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveCascadedPaths("AppEnv", null));
    }

    @Test
    void resolveCascadedPathsPropagatesPermissionException() {
        PermissionService permissionService = mock(PermissionService.class);
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        MetaField rel = mockField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField status = mockField("DesignDeployment", "deployStatus", FieldType.OPTION, null);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            stubMapper(mapper);
            mm.when(() -> ModelManager.validateModel("AppEnv")).thenAnswer(inv -> null);
            mm.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("DesignDeployment", "deployStatus")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mm.when(() -> ModelManager.getModelField("DesignDeployment", "deployStatus")).thenReturn(status);

            doThrow(new PermissionException("forbidden"))
                    .when(permissionService).checkModelCascadeFieldsAccess(
                            eq("AppEnv"), any(), eq(AccessType.READ));

            assertThrows(PermissionException.class,
                    () -> service.resolveCascadedPaths("AppEnv", List.of("lastDeploymentId.deployStatus")));
        }
    }

    @Test
    void resolveCascadedPathsDeduplicatesClosureForSharedAncestors() {
        PermissionService permissionService = mock(PermissionService.class);
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        MetaField rel = mockField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField status = mockField("DesignDeployment", "deployStatus", FieldType.OPTION, null);
        MetaField finished = mockField("DesignDeployment", "finishedTime", FieldType.DATE_TIME, null);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            stubMapper(mapper);
            mm.when(() -> ModelManager.validateModel("AppEnv")).thenAnswer(inv -> null);
            mm.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("DesignDeployment", "deployStatus")).thenReturn(true);
            mm.when(() -> ModelManager.existField("DesignDeployment", "finishedTime")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mm.when(() -> ModelManager.getModelField("DesignDeployment", "deployStatus")).thenReturn(status);
            mm.when(() -> ModelManager.getModelField("DesignDeployment", "finishedTime")).thenReturn(finished);

            ResolveCascadedPathsResponse response = service.resolveCascadedPaths(
                    "AppEnv", List.of(
                            "lastDeploymentId.deployStatus",
                            "lastDeploymentId.finishedTime"));

            // Shared ancestor DesignDeployment is deduped; root is not in the closure.
            assertEquals(1, response.getMetaModels().size());
            assertEquals("DesignDeployment", response.getMetaModels().get(0).getModelName());
        }
    }

    @Test
    void upgradeMetadataFailsWhenDeleteTargetsDoNotExist() {
        MetadataServiceImpl service = new MetadataServiceImpl();
        @SuppressWarnings("unchecked")
        ModelService<Serializable> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);

        MetadataUpgradePackage metadataPackage = new MetadataUpgradePackage();
        metadataPackage.setModelName("SysField");
        metadataPackage.setDeleteIds(List.of(10L, 11L));
        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class)))
                .thenReturn(List.of(Map.of(ModelConstant.ID, 10L)));

        SystemConfig previous = SystemConfig.env;
        SystemConfig config = new SystemConfig();
        config.setEnableInsertId(true);
        SystemConfig.env = config;
        try {
            assertThrows(IllegalArgumentException.class, () -> service.upgradeMetadata(List.of(metadataPackage)));
            verify(modelService, never()).deleteByIds(eq("SysField"), Mockito.anyList());
        } finally {
            SystemConfig.env = previous;
        }
    }
}
