package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.dto.MetadataUpgradePackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MetadataServiceImplTest {

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
