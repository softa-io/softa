package io.softa.starter.studio.release.version;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.es.service.ChangeLogService;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.version.impl.VersionControlImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VersionControlImplTest {

    @Test
    void collectModelChangesUsesSingleBulkQueryAcrossVersionedModels() {
        VersionControlImpl versionControl = new VersionControlImpl();
        ChangeLogService changeLogService = mock(ChangeLogService.class);
        @SuppressWarnings("unchecked")
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(versionControl, "changeLogService", changeLogService);
        ReflectionTestUtils.setField(versionControl, "modelService", modelService);

        ChangeLog modelDelete = deleteLog("DesignModel", 101L, Map.of("modelName", "Account"));
        ChangeLog fieldDelete = deleteLog("DesignField", 202L, Map.of("modelName", "Account", "fieldName", "name"));
        when(changeLogService.searchByCorrelationIds(anyList(), eq(List.of("11", "12"))))
                .thenReturn(List.of(modelDelete, fieldDelete));

        List<ModelChangesDTO> result = versionControl.collectModelChanges(List.of(11L, 12L));

        assertEquals(2, result.size());
        assertEquals(List.of("DesignModel", "DesignField"), result.stream().map(ModelChangesDTO::getModelName).toList());
        assertTrue(result.stream().allMatch(dto -> dto.getDeletedRows().size() == 1));
        verify(changeLogService).searchByCorrelationIds(anyList(), eq(List.of("11", "12")));
    }

    @Test
    void collectModelChangesRebuildsCreatedRowsFromHistoricalLogs() {
        VersionControlImpl versionControl = new VersionControlImpl();
        ChangeLogService changeLogService = mock(ChangeLogService.class);
        @SuppressWarnings("unchecked")
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(versionControl, "changeLogService", changeLogService);
        ReflectionTestUtils.setField(versionControl, "modelService", modelService);

        ChangeLog createLog = createLog("DesignModel", 101L, Map.of(
                ModelConstant.ID, 101L,
                "modelName", "Account",
                "displayName", "Account"));
        ChangeLog updateLog = updateLog("DesignModel", 101L, Map.of(
                "displayName", "Customer",
                "description", "renamed"));
        when(changeLogService.searchByCorrelationIds(anyList(), eq(List.of("11"))))
                .thenReturn(List.of(createLog, updateLog));

        List<ModelChangesDTO> result = versionControl.collectModelChanges(List.of(11L));

        assertEquals(1, result.size());
        ModelChangesDTO modelChangesDTO = result.getFirst();
        assertEquals(1, modelChangesDTO.getCreatedRows().size());
        RowChangeDTO createdRow = modelChangesDTO.getCreatedRows().getFirst();
        assertEquals(Map.of(
                ModelConstant.ID, 101L,
                "modelName", "Account",
                "displayName", "Customer",
                "description", "renamed"), createdRow.getCurrentData());
        assertEquals("updater", createdRow.getLastChangedBy());
        verify(changeLogService).searchByCorrelationIds(anyList(), eq(List.of("11")));
        verifyNoInteractions(modelService);
    }

    @Test
    void collectModelChangesLoadsCurrentDataForUpdatedRows() {
        VersionControlImpl versionControl = new VersionControlImpl();
        ChangeLogService changeLogService = mock(ChangeLogService.class);
        @SuppressWarnings("unchecked")
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(versionControl, "changeLogService", changeLogService);
        ReflectionTestUtils.setField(versionControl, "modelService", modelService);

        ChangeLog firstUpdate = updateLog("DesignModel", 101L,
                Map.of("displayName", "Account"),
                Map.of("displayName", "Customer"));
        ChangeLog secondUpdate = updateLog("DesignModel", 101L,
                Map.of("description", "old"),
                Map.of("description", "renamed"));
        when(changeLogService.searchByCorrelationIds(anyList(), eq(List.of("11"))))
                .thenReturn(List.of(firstUpdate, secondUpdate));
        when(modelService.searchList(eq("DesignModel"), any()))
                .thenReturn(List.of(Map.of(
                        ModelConstant.ID, 101L,
                        "modelName", "Account",
                        "displayName", "Customer",
                        "description", "renamed")));

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.isSoftDeleted("DesignModel")).thenReturn(false);
            modelManager.when(() -> ModelManager.getModelFieldsWithoutXToMany("DesignModel"))
                    .thenReturn(Set.of(ModelConstant.ID, "modelName", "displayName", "description"));

            List<ModelChangesDTO> result = versionControl.collectModelChanges(List.of(11L));

            assertEquals(1, result.size());
            ModelChangesDTO modelChangesDTO = result.getFirst();
            assertEquals(1, modelChangesDTO.getUpdatedRows().size());
            RowChangeDTO updatedRow = modelChangesDTO.getUpdatedRows().getFirst();
            assertEquals(Map.of(
                    ModelConstant.ID, 101L,
                    "modelName", "Account",
                    "displayName", "Customer",
                    "description", "renamed"), updatedRow.getCurrentData());
            assertEquals(Map.of("displayName", "Account", "description", "old"), updatedRow.getDataBeforeChange());
            assertEquals(Map.of("displayName", "Customer", "description", "renamed"), updatedRow.getDataAfterChange());
            verify(modelService).searchList(eq("DesignModel"), any());
        }
    }

    @Test
    void collectModelChangesUsesDeleteSnapshotWithoutDatabaseLookup() {
        VersionControlImpl versionControl = new VersionControlImpl();
        ChangeLogService changeLogService = mock(ChangeLogService.class);
        @SuppressWarnings("unchecked")
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(versionControl, "changeLogService", changeLogService);
        ReflectionTestUtils.setField(versionControl, "modelService", modelService);

        ChangeLog deleteLog = deleteLog("DesignModel", 101L, Map.of(
                ModelConstant.ID, 101L,
                "modelName", "Account",
                "displayName", "Account"));
        when(changeLogService.searchByCorrelationIds(anyList(), eq(List.of("11"))))
                .thenReturn(List.of(deleteLog));

        List<ModelChangesDTO> result = versionControl.collectModelChanges(List.of(11L));

        assertEquals(1, result.size());
        ModelChangesDTO modelChangesDTO = result.getFirst();
        assertEquals(1, modelChangesDTO.getDeletedRows().size());
        assertEquals(Map.of(
                ModelConstant.ID, 101L,
                "modelName", "Account",
                "displayName", "Account"), modelChangesDTO.getDeletedRows().getFirst().getCurrentData());
        verifyNoInteractions(modelService);
    }

    private ChangeLog deleteLog(String model, Long rowId, Map<String, Object> dataBeforeChange) {
        ChangeLog changeLog = new ChangeLog();
        changeLog.setModel(model);
        changeLog.setRowId(String.valueOf(rowId));
        changeLog.setAccessType(AccessType.DELETE);
        changeLog.setDataBeforeChange(dataBeforeChange);
        changeLog.setChangedById(1L);
        changeLog.setChangedBy("tester");
        changeLog.setChangedTime("2026-03-23T20:00:00");
        return changeLog;
    }

    private ChangeLog createLog(String model, Long rowId, Map<String, Object> dataAfterChange) {
        ChangeLog changeLog = new ChangeLog();
        changeLog.setModel(model);
        changeLog.setRowId(String.valueOf(rowId));
        changeLog.setAccessType(AccessType.CREATE);
        changeLog.setDataAfterChange(dataAfterChange);
        changeLog.setChangedById(1L);
        changeLog.setChangedBy("creator");
        changeLog.setChangedTime("2026-03-23T20:00:00");
        return changeLog;
    }

    private ChangeLog updateLog(String model, Long rowId, Map<String, Object> dataAfterChange) {
        return updateLog(model, rowId, null, dataAfterChange);
    }

    private ChangeLog updateLog(String model, Long rowId,
                                Map<String, Object> dataBeforeChange,
                                Map<String, Object> dataAfterChange) {
        ChangeLog changeLog = new ChangeLog();
        changeLog.setModel(model);
        changeLog.setRowId(String.valueOf(rowId));
        changeLog.setAccessType(AccessType.UPDATE);
        changeLog.setDataBeforeChange(dataBeforeChange);
        changeLog.setDataAfterChange(dataAfterChange);
        changeLog.setChangedById(2L);
        changeLog.setChangedBy("updater");
        changeLog.setChangedTime("2026-03-24T09:00:00");
        return changeLog;
    }
}
