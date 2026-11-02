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
import io.softa.starter.metadata.checksum.AggregateChecksumIndex;
import io.softa.starter.metadata.controller.dto.MetaFieldDTO;
import io.softa.starter.metadata.controller.dto.MetaModelDTO;
import io.softa.starter.metadata.controller.dto.PathResolution;
import io.softa.starter.metadata.controller.dto.ResolveCascadedPathsResponse;
import io.softa.starter.metadata.dto.*;
import io.softa.starter.metadata.message.InnerBroadcastProducer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
        // exportRuntimeMetadata's identity handshake reads system.app-code.
        if (SystemConfig.env.getAppCode() == null) {
            SystemConfig.env.setAppCode("demo-app");
        }
    }

    @Test
    void exportRuntimeMetadataScopesMainModelByAppCodeOnly() {
        ModelService<Serializable> modelService = mockModelService();
        MetadataServiceImpl service = serviceWithModelService(modelService);

        List<Map<String, Object>> rows = List.of(Map.of("id", 1L, "fieldName", "name", "appCode", "demo-app"));
        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class))).thenReturn(rows);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SysField", "appCode")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany("SysField"))
                    .thenReturn(Set.of("id", "fieldName", "appCode"));

            List<Map<String, Object>> result = service.exportRuntimeMetadata("SysField", "demo-app");

            assertEquals(rows, result);
            // Scope is appCode ONLY: studio is the complete source of truth for the app's
            // metadata, so the export returns the full catalog.
            verify(modelService).searchList(eq("SysField"), Mockito.argThat(query ->
                    query != null
                            && query.getFields().containsAll(Set.of("id", "fieldName", "appCode"))
                            && query.getFilters() != null
                            && query.getFilters().toString().contains("appCode")));
        }
    }

    @Test
    void exportRuntimeMetadataNarrowsByAggregateKeys() {
        // Incremental fetch: a non-null keyColumn ANDs keyColumn IN keyValues onto the
        // app scope, so the export returns only the requested aggregates.
        ModelService<Serializable> modelService = mockModelService();
        MetadataServiceImpl service = serviceWithModelService(modelService);

        List<Map<String, Object>> rows = List.of(Map.of("id", 1L, "modelName", "Account"));
        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class))).thenReturn(rows);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SysField", "appCode")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("SysField", "modelName")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany("SysField"))
                    .thenReturn(Set.of("id", "modelName", "appCode"));

            List<Map<String, Object>> result = service.exportRuntimeMetadata(
                    "SysField", "demo-app", "modelName", List.of("Account", "Order"));

            assertEquals(rows, result);
            verify(modelService).searchList(eq("SysField"), Mockito.argThat(query ->
                    query != null
                            && query.getFilters() != null
                            && query.getFilters().toString().contains("appCode")
                            && query.getFilters().toString().contains("modelName")));
        }
    }

    @Test
    void exportRuntimeMetadataReturnsEmptyForEmptyAggregateKeySet() {
        // An empty key set with a non-null keyColumn means "fetch nothing" — no query is issued at all
        // (distinct from a null keyColumn, the full export).
        ModelService<Serializable> modelService = mockModelService();
        MetadataServiceImpl service = serviceWithModelService(modelService);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SysField", "appCode")).thenReturn(true);

            List<Map<String, Object>> result = service.exportRuntimeMetadata(
                    "SysField", "demo-app", "modelName", List.of());

            assertEquals(List.of(), result);
            verify(modelService, never()).searchList(Mockito.anyString(), Mockito.any(FlexQuery.class));
        }
    }

    @Test
    void exportRuntimeMetadataRejectsUnknownNarrowingColumn() {
        // A client-supplied narrowing column that is not a real column is rejected (defense-in-depth on
        // a signed-but-still-external request).
        ModelService<Serializable> modelService = mockModelService();
        MetadataServiceImpl service = serviceWithModelService(modelService);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SysField", "appCode")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("SysField", "bogus")).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> service.exportRuntimeMetadata("SysField", "demo-app", "bogus", List.of("x")));
            verify(modelService, never()).searchList(Mockito.anyString(), Mockito.any(FlexQuery.class));
        }
    }

    @Test
    void exportRuntimeMetadataRejectsForeignAppCode() {
        ModelService<Serializable> modelService = mockModelService();
        MetadataServiceImpl service = serviceWithModelService(modelService);

        // The handshake must fail-closed before any query when the requested
        // identity is not this runtime's app.
        assertThrows(IllegalArgumentException.class,
                () -> service.exportRuntimeMetadata("SysField", "other-app"));
        verify(modelService, never()).searchList(Mockito.anyString(), Mockito.any(FlexQuery.class));
    }

    @Test
    void exportRuntimeMetadataResolvesTransModelViaParentIds() {
        ModelService<Serializable> modelService = mockModelService();
        MetadataServiceImpl service = serviceWithModelService(modelService);

        List<Map<String, Object>> parentRows = List.of(Map.of("id", 7L), Map.of("id", 8L));
        List<Map<String, Object>> transRows = List.of(Map.of("id", 100L, "rowId", 7L, "languageCode", "en"));
        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class))).thenReturn(parentRows);
        when(modelService.searchList(eq("SysFieldTrans"), Mockito.any(FlexQuery.class))).thenReturn(transRows);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SysField", "appCode")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany("SysFieldTrans"))
                    .thenReturn(Set.of("id", "rowId", "languageCode"));

            List<Map<String, Object>> result = service.exportRuntimeMetadata("SysFieldTrans", "demo-app");

            assertEquals(transRows, result);
            verify(modelService).searchList(eq("SysField"), Mockito.argThat(query ->
                    query != null
                            && query.getFields().contains(ModelConstant.ID)
                            && query.getFilters() != null
                            && query.getFilters().toString().contains("appCode")));
            verify(modelService).searchList(eq("SysFieldTrans"), Mockito.argThat(query ->
                    query != null
                            && query.getFilters() != null
                            && query.getFilters().toString().contains("rowId")));
        }
    }

    @Test
    void exportRuntimeMetadataReturnsEmptyWhenTransParentHasNoRows() {
        ModelService<Serializable> modelService = mockModelService();
        MetadataServiceImpl service = serviceWithModelService(modelService);

        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class))).thenReturn(List.of());

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SysField", "appCode")).thenReturn(true);

            List<Map<String, Object>> result = service.exportRuntimeMetadata("SysFieldTrans", "demo-app");

            assertEquals(List.of(), result);
            verify(modelService, never()).searchList(eq("SysFieldTrans"), Mockito.any(FlexQuery.class));
        }
    }

    @Test
    void exportRuntimeMetadataRejectsUnscopableModel() {
        ModelService<Serializable> modelService = mockModelService();
        MetadataServiceImpl service = serviceWithModelService(modelService);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("SomeOtherModel", "appCode")).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> service.exportRuntimeMetadata("SomeOtherModel", "demo-app"));
            verify(modelService, never()).searchList(Mockito.anyString(), Mockito.any(FlexQuery.class));
        }
    }

    @Test
    void exportRuntimeChecksumsAssemblesAggregatesByCode() {
        ModelService<Serializable> modelService = mockModelService();
        MetadataServiceImpl service = serviceWithModelService(modelService);

        Map<String, Object> modelRow = Map.of("id", 1L, "modelName", "Customer",
                "tableName", "customer", "appCode", "demo-app");
        Map<String, Object> fieldRow = Map.of("id", 10L, "modelName", "Customer",
                "fieldName", "code", "columnName", "code", "appCode", "demo-app");
        when(modelService.searchList(eq("SysModel"), any(FlexQuery.class))).thenReturn(List.of(modelRow));
        when(modelService.searchList(eq("SysField"), any(FlexQuery.class))).thenReturn(List.of(fieldRow));
        when(modelService.searchList(eq("SysModelIndex"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("SysOptionSet"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("SysOptionItem"), any(FlexQuery.class))).thenReturn(List.of());

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            for (String m : List.of("SysModel", "SysField", "SysModelIndex", "SysOptionSet", "SysOptionItem")) {
                modelManager.when(() -> ModelManager.existField(m, "appCode")).thenReturn(true);
                modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany(m)).thenReturn(Set.of("id", "modelName"));
            }

            RuntimeChecksumsDTO dto = service.exportRuntimeChecksums("demo-app");

            // The runtime aggregate checksum equals the shared assembler over the same rows (code-linked).
            String expected = AggregateChecksumIndex.models(List.of(modelRow), List.of(fieldRow), List.of(),
                    "modelName", "modelName").get("Customer");
            assertEquals(expected, dto.models().get("Customer"));
            assertTrue(dto.optionSets().isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Serializable> mockModelService() {
        return mock(ModelService.class);
    }

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

    private static MetadataServiceImpl serviceWithModelService(ModelService<Serializable> modelService) {
        return new MetadataServiceImpl(modelService, mock(InnerBroadcastProducer.class),
                mock(PermissionService.class));
    }

    private static MetadataServiceImpl serviceWithPermission(PermissionService permissionService) {
        return new MetadataServiceImpl(mockModelService(), mock(InnerBroadcastProducer.class),
                permissionService);
    }

    @Test
    void getMetaModelDtoLeavesOutFieldsWithheldFromTheCaller() {
        // A sensitive field no role of the caller grants never shows its value, so its definition is
        // not served either; the model's other fields, and other models' withheld lists, do not matter.
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getHiddenSensitiveFields())
                .thenReturn(Map.of("Staff", Set.of("basePay"), "Contract", Set.of("name")));
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        try (MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            mapper.when(() -> MetadataDtoMapper.toModelDTO("Staff"))
                    .thenAnswer(inv -> modelDto("Staff", "name", "basePay", "payGrade"));

            MetaModelDTO dto = service.getMetaModelDTO("Staff");

            assertEquals(Set.of("name", "payGrade"), dto.getModelFields().keySet());
        }
    }

    @Test
    void getMetaModelDtoServesEveryFieldWhenNothingIsWithheld() {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getHiddenSensitiveFields()).thenReturn(Map.of());
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        try (MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            mapper.when(() -> MetadataDtoMapper.toModelDTO("Staff"))
                    .thenAnswer(inv -> modelDto("Staff", "name", "basePay"));

            assertEquals(Set.of("name", "basePay"), service.getMetaModelDTO("Staff").getModelFields().keySet());
        }
    }

    @Test
    void resolveCascadedPathsTreatsAWithheldFieldAsNotFound() {
        // A path landing on a withheld field resolves as that field missing — the same answer
        // getMetaModel gives by leaving it out — and the related model it reaches is served without it.
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getHiddenSensitiveFields()).thenReturn(Map.of("Staff", Set.of("basePay")));
        MetadataServiceImpl service = serviceWithPermission(permissionService);

        MetaField manager = mockField("Leave", "managerId", FieldType.MANY_TO_ONE, "Staff");
        MetaField basePay = mockField("Staff", "basePay", FieldType.BIG_DECIMAL, null);
        MetaField name = mockField("Staff", "name", FieldType.STRING, null);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            stubMapper(mapper);
            mapper.when(() -> MetadataDtoMapper.toModelDTO("Staff"))
                    .thenAnswer(inv -> modelDto("Staff", "name", "basePay"));
            mm.when(() -> ModelManager.validateModel("Leave")).thenAnswer(inv -> null);
            mm.when(() -> ModelManager.existField("Leave", "managerId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Staff", "basePay")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Staff", "name")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("Leave", "managerId")).thenReturn(manager);
            mm.when(() -> ModelManager.getModelField("Staff", "basePay")).thenReturn(basePay);
            mm.when(() -> ModelManager.getModelField("Staff", "name")).thenReturn(name);

            ResolveCascadedPathsResponse response = service.resolveCascadedPaths(
                    "Leave", List.of("managerId.basePay", "managerId.name"));

            PathResolution withheld = response.getResolutions().get(0);
            assertFalse(withheld.isOk());
            assertEquals(CascadeFieldWalker.ErrorKind.FIELD_NOT_FOUND, withheld.getErrorCode());
            assertEquals(Integer.valueOf(1), withheld.getErrorAt());
            assertEquals("Field 'basePay' not found on model 'Staff'", withheld.getMessage());

            assertTrue(response.getResolutions().get(1).isOk());
            assertEquals(1, response.getMetaModels().size());
            assertEquals(Set.of("name"), response.getMetaModels().get(0).getModelFields().keySet());
        }
    }

    /** A bank account holds nothing but sensitive fields and plumbing: its id and the staff it belongs to. */
    private static List<MetaField> bankAccountFields() {
        return List.of(
                mockField("BankAccount", "id", FieldType.LONG, null),
                mockField("BankAccount", "staffId", FieldType.MANY_TO_ONE, "Staff"),
                mockField("BankAccount", "accountNumber", FieldType.STRING, null),
                mockField("BankAccount", "bank", FieldType.MANY_TO_ONE, "Bank"));
    }

    @Test
    void getMetaModelDtoLeavesOutARelationOntoAModelTheCallerCanReadNothingOf() {
        // Every field of the bank account but its plumbing is withheld, so the relation onto it could only
        // offer a column, an export field and a filter that come back empty; a relation onto a model with
        // something left to read stays.
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getHiddenSensitiveFields())
                .thenReturn(Map.of("BankAccount", Set.of("accountNumber", "bank")));
        MetadataServiceImpl service = serviceWithPermission(permissionService);
        List<MetaField> bankAccount = bankAccountFields();

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            mm.when(() -> ModelManager.getModelFields("BankAccount")).thenReturn(bankAccount);
            mapper.when(() -> MetadataDtoMapper.toModelDTO("Staff")).thenAnswer(inv -> {
                MetaModelDTO dto = modelDto("Staff", "name");
                dto.getModelFields().put("bankAccountId", relationDto("Staff", "bankAccountId", "BankAccount"));
                dto.getModelFields().put("departmentId", relationDto("Staff", "departmentId", "Department"));
                return dto;
            });

            MetaModelDTO dto = service.getMetaModelDTO("Staff");

            assertEquals(Set.of("name", "departmentId"), dto.getModelFields().keySet());
        }
    }

    @Test
    void resolveCascadedPathsStopsAtARelationOntoAModelTheCallerCanReadNothingOf() {
        // The same answer getMetaModel gives by leaving the relation out: the path fails at the relation
        // itself, not at the withheld field past it.
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getHiddenSensitiveFields())
                .thenReturn(Map.of("BankAccount", Set.of("accountNumber", "bank")));
        MetadataServiceImpl service = serviceWithPermission(permissionService);
        List<MetaField> bankAccount = bankAccountFields();
        MetaField bankAccountId = mockField("Staff", "bankAccountId", FieldType.ONE_TO_ONE, "BankAccount");
        MetaField departmentId = mockField("Staff", "departmentId", FieldType.MANY_TO_ONE, "Department");
        MetaField departmentName = mockField("Department", "name", FieldType.STRING, null);

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<MetadataDtoMapper> mapper = Mockito.mockStatic(MetadataDtoMapper.class)) {
            stubMapper(mapper);
            mm.when(() -> ModelManager.validateModel("Staff")).thenAnswer(inv -> null);
            mm.when(() -> ModelManager.getModelFields("BankAccount")).thenReturn(bankAccount);
            mm.when(() -> ModelManager.existField("Staff", "bankAccountId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Staff", "departmentId")).thenReturn(true);
            mm.when(() -> ModelManager.existField("BankAccount", "accountNumber")).thenReturn(true);
            mm.when(() -> ModelManager.existField("Department", "name")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("Staff", "bankAccountId")).thenReturn(bankAccountId);
            mm.when(() -> ModelManager.getModelField("Staff", "departmentId")).thenReturn(departmentId);
            mm.when(() -> ModelManager.getModelField("BankAccount", "accountNumber")).thenReturn(bankAccount.get(2));
            mm.when(() -> ModelManager.getModelField("Department", "name")).thenReturn(departmentName);

            ResolveCascadedPathsResponse response = service.resolveCascadedPaths(
                    "Staff", List.of("bankAccountId.accountNumber", "departmentId.name"));

            PathResolution withheld = response.getResolutions().get(0);
            assertFalse(withheld.isOk());
            assertEquals(CascadeFieldWalker.ErrorKind.FIELD_NOT_FOUND, withheld.getErrorCode());
            assertEquals(Integer.valueOf(0), withheld.getErrorAt());
            assertEquals("Field 'bankAccountId' not found on model 'Staff'", withheld.getMessage());

            assertTrue(response.getResolutions().get(1).isOk());
            assertEquals(List.of("Department"),
                    response.getMetaModels().stream().map(MetaModelDTO::getModelName).toList());
        }
    }

    private static MetaFieldDTO relationDto(String modelName, String fieldName, String relatedModel) {
        MetaFieldDTO field = new MetaFieldDTO();
        field.setFieldName(fieldName);
        field.setModelName(modelName);
        field.setFieldType(FieldType.MANY_TO_ONE);
        field.setRelatedModel(relatedModel);
        return field;
    }

    private static MetaModelDTO modelDto(String modelName, String... fieldNames) {
        MetaModelDTO dto = new MetaModelDTO();
        dto.setModelName(modelName);
        Map<String, MetaFieldDTO> fields = new java.util.HashMap<>();
        for (String fieldName : fieldNames) {
            MetaFieldDTO field = new MetaFieldDTO();
            field.setFieldName(fieldName);
            field.setModelName(modelName);
            fields.put(fieldName, field);
        }
        dto.setModelFields(fields);
        return dto;
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

}
