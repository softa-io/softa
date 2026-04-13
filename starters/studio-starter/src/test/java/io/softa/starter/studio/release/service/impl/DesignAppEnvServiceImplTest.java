package io.softa.starter.studio.release.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.metadata.service.MetadataService;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.entity.DesignAppEnvSnapshot;
import io.softa.starter.studio.release.enums.DesignAppEnvType;
import io.softa.starter.studio.release.service.DesignAppEnvSnapshotService;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignAppEnvServiceImplTest {

    @Test
    void takeSnapshotOverwritesRowsByIdWhenBusinessKeyChanges() {
        DesignAppEnvServiceImpl service = Mockito.spy(new DesignAppEnvServiceImpl());
        DesignAppEnvSnapshotService snapshotService = mock(DesignAppEnvSnapshotService.class);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "metadataService", mock(MetadataService.class));
        ReflectionTestUtils.setField(service, "remoteApiClient", mock(RemoteApiClient.class));
        ReflectionTestUtils.setField(service, "environment", mock(Environment.class));

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvType(DesignAppEnvType.DEV);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);

        DesignAppEnvSnapshot existingSnapshot = new DesignAppEnvSnapshot();
        existingSnapshot.setEnvId(1L);
        existingSnapshot.setAppId(100L);
        existingSnapshot.setSnapshot(JsonUtils.objectToJsonNode(Map.of(
                "DesignField", List.of(Map.of(
                        ModelConstant.ID, 10L,
                        "modelName", "Account",
                        "fieldName", "name",
                        "columnName", "name")))));
        when(snapshotService.searchOne(any(FlexQuery.class))).thenReturn(Optional.of(existingSnapshot));

        RowChangeDTO updatedRow = new RowChangeDTO("DesignField", 10L);
        updatedRow.setAccessType(AccessType.UPDATE);
        updatedRow.setCurrentData(Map.of(
                ModelConstant.ID, 10L,
                "modelName", "Account",
                "fieldName", "customerName",
                "columnName", "customer_name"));
        updatedRow.setDataAfterChange(Map.of(
                "fieldName", "customerName",
                "columnName", "customer_name"));
        ModelChangesDTO changes = new ModelChangesDTO("DesignField");
        changes.addUpdatedRow(updatedRow);

        service.takeSnapshot(1L, 200L, List.of(changes));

        verify(snapshotService).updateOne(existingSnapshot);
        Map<String, List<Map<String, Object>>> snapshotData = JsonUtils.jsonNodeToObject(
                existingSnapshot.getSnapshot(), new TypeReference<>() {});
        List<Map<String, Object>> rows = snapshotData.get("DesignField");
        assertEquals(1, rows.size());
        assertEquals(10L, ((Number) rows.getFirst().get(ModelConstant.ID)).longValue());
        assertEquals("customerName", rows.getFirst().get("fieldName"));
        assertEquals("customer_name", rows.getFirst().get("columnName"));
    }

    @Test
    void compareDesignWithRuntimeMatchesRowsByIdInsteadOfBusinessKey() {
        DesignAppEnvServiceImpl service = Mockito.spy(new DesignAppEnvServiceImpl());
        DesignAppEnvSnapshotService snapshotService = mock(DesignAppEnvSnapshotService.class);
        MetadataService metadataService = mock(MetadataService.class);
        Environment environment = mock(Environment.class);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "metadataService", metadataService);
        ReflectionTestUtils.setField(service, "remoteApiClient", mock(RemoteApiClient.class));
        ReflectionTestUtils.setField(service, "environment", environment);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvType(DesignAppEnvType.DEV);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);

        DesignAppEnvSnapshot snapshot = new DesignAppEnvSnapshot();
        snapshot.setEnvId(1L);
        snapshot.setAppId(100L);
        snapshot.setSnapshot(JsonUtils.objectToJsonNode(Map.of(
                "DesignField", List.of(Map.of(
                        ModelConstant.ID, 10L,
                        "modelName", "Account",
                        "fieldName", "customerName",
                        "columnName", "customer_name")))));
        when(snapshotService.searchOne(any(FlexQuery.class))).thenReturn(Optional.of(snapshot));
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        when(metadataService.exportRuntimeMetadata(Mockito.anyString())).thenReturn(List.of());
        when(metadataService.exportRuntimeMetadata(eq("SysField"))).thenReturn(List.of(Map.of(
                ModelConstant.ID, 10L,
                "modelName", "Account",
                "fieldName", "name",
                "columnName", "name")));

        Set<String> compareFields = Set.of(ModelConstant.ID, "modelName", "fieldName", "columnName");
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            for (Map.Entry<String, String> entry : MetadataConstant.VERSION_CONTROL_MODELS.entrySet()) {
                modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany(entry.getKey()))
                        .thenReturn(compareFields);
                modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany(entry.getValue()))
                        .thenReturn(compareFields);
            }

            List<ModelChangesDTO> result = service.compareDesignWithRuntime(1L);

            assertEquals(1, result.size());
            ModelChangesDTO diff = result.getFirst();
            assertEquals("DesignField", diff.getModelName());
            assertTrue(diff.getCreatedRows().isEmpty());
            assertTrue(diff.getDeletedRows().isEmpty());
            assertEquals(1, diff.getUpdatedRows().size());
            RowChangeDTO updatedRow = diff.getUpdatedRows().getFirst();
            assertEquals(10L, updatedRow.getRowId());
            assertEquals(Map.of(
                    "fieldName", "name",
                    "columnName", "name"), updatedRow.getDataBeforeChange());
            assertEquals(Map.of(
                    "fieldName", "customerName",
                    "columnName", "customer_name"), updatedRow.getDataAfterChange());
            assertEquals("customerName", updatedRow.getCurrentData().get("fieldName"));
        }
    }
}
