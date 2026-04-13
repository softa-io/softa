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
import io.softa.framework.web.dto.MetadataUpgradePackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataServiceImplTest {

    @Test
    void exportRuntimeMetadataUsesRequestedModelName() {
        MetadataServiceImpl service = new MetadataServiceImpl();
        @SuppressWarnings("unchecked")
        ModelService<Serializable> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);

        List<Map<String, Object>> rows = List.of(Map.of("id", 1L, "fieldName", "name"));
        when(modelService.searchList(eq("SysField"), Mockito.any(FlexQuery.class))).thenReturn(rows);

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany("SysField"))
                    .thenReturn(Set.of("id", "fieldName"));

            List<Map<String, Object>> result = service.exportRuntimeMetadata("SysField");

            assertEquals(rows, result);
            verify(modelService).searchList(eq("SysField"), Mockito.argThat(query ->
                    query != null
                            && query.getFields().size() == 2
                            && query.getFields().containsAll(Set.of("id", "fieldName"))));
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
