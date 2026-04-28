package io.softa.starter.studio.release.service.impl;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.SFunction;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.entity.DesignAppEnvDrift;
import io.softa.starter.studio.release.entity.DesignAppEnvSnapshot;
import io.softa.starter.studio.release.entity.DesignAppVersion;
import io.softa.starter.studio.release.enums.DesignAppEnvStatus;
import io.softa.starter.studio.release.enums.DesignAppEnvType;
import io.softa.starter.studio.release.enums.DesignAppVersionStatus;
import io.softa.starter.studio.release.enums.DesignDriftCheckStatus;
import io.softa.starter.studio.release.service.DesignAppEnvDriftService;
import io.softa.starter.studio.release.service.DesignAppEnvSnapshotService;
import io.softa.starter.studio.release.service.DesignAppVersionService;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignAppEnvServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void takeSnapshotUpsertsByAppIdEnvIdDeploymentId() {
        DesignAppEnvServiceImpl service = Mockito.spy(new DesignAppEnvServiceImpl());
        DesignAppEnvSnapshotService snapshotService = mock(DesignAppEnvSnapshotService.class);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "driftService", mock(DesignAppEnvDriftService.class));
        ReflectionTestUtils.setField(service, "remoteApiClient", mock(RemoteApiClient.class));

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvType(DesignAppEnvType.DEV);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);

        // Previous snapshot acts as the baseline; new deployment merges an UPDATE on top.
        DesignAppEnvSnapshot existingSnapshot = new DesignAppEnvSnapshot();
        existingSnapshot.setEnvId(1L);
        existingSnapshot.setAppId(100L);
        existingSnapshot.setDeploymentId(199L);
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

        ArgumentCaptor<List<DesignAppEnvSnapshot>> entitiesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<SFunction<DesignAppEnvSnapshot, ?>>> uniqueCaptor = ArgumentCaptor.forClass(List.class);
        verify(snapshotService).createOrUpdate(entitiesCaptor.capture(), uniqueCaptor.capture());

        List<DesignAppEnvSnapshot> written = entitiesCaptor.getValue();
        assertEquals(1, written.size());
        DesignAppEnvSnapshot snapshot = written.getFirst();
        assertEquals(100L, snapshot.getAppId());
        assertEquals(1L, snapshot.getEnvId());
        assertEquals(200L, snapshot.getDeploymentId(),
                "deploymentId must be the current deployment, not the previous one");
        assertEquals(3, uniqueCaptor.getValue().size(),
                "Unique constraint should be (appId, envId, deploymentId)");

        Map<String, List<Map<String, Object>>> snapshotData = JsonUtils.jsonNodeToObject(
                snapshot.getSnapshot(), new TypeReference<>() {});
        List<Map<String, Object>> rows = snapshotData.get("DesignField");
        assertEquals(1, rows.size());
        assertEquals(10L, ((Number) rows.getFirst().get(ModelConstant.ID)).longValue());
        assertEquals("customerName", rows.getFirst().get("fieldName"));
        assertEquals("customer_name", rows.getFirst().get("columnName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshDriftStoresUpdateDiffWhenRuntimeColumnDiffersFromSnapshot() {
        DesignAppEnvServiceImpl service = Mockito.spy(new DesignAppEnvServiceImpl());
        DesignAppEnvSnapshotService snapshotService = mock(DesignAppEnvSnapshotService.class);
        DesignAppEnvDriftService driftService = mock(DesignAppEnvDriftService.class);
        RemoteApiClient remoteApiClient = mock(RemoteApiClient.class);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "driftService", driftService);
        ReflectionTestUtils.setField(service, "remoteApiClient", remoteApiClient);

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
        when(remoteApiClient.fetchRuntimeMetadata(any(), Mockito.anyString())).thenReturn(List.of());
        when(remoteApiClient.fetchRuntimeMetadata(any(), eq("SysField"))).thenReturn(List.of(Map.of(
                ModelConstant.ID, 10L,
                "modelName", "Account",
                "fieldName", "name",
                "columnName", "name")));

        // Stubbed via the spy because `getComparableFields` is invoked on CompletableFuture
        // worker threads and `Mockito.mockStatic` is thread-local. This is the set AFTER
        // `getComparableFields` would have stripped identity / audit fields.
        Set<String> compareFields = Set.of("modelName", "fieldName", "columnName");
        doReturn(compareFields).when(service).getComparableFields(Mockito.anyString(), Mockito.anyString());

        service.refreshDrift(1L);

        ArgumentCaptor<List<DesignAppEnvDrift>> captor = ArgumentCaptor.forClass(List.class);
        verify(driftService).createOrUpdate(captor.capture(), anyList());
        DesignAppEnvDrift persisted = captor.getValue().getFirst();
        assertEquals(100L, persisted.getAppId());
        assertEquals(1L, persisted.getEnvId());
        assertEquals(DesignDriftCheckStatus.SUCCESS, persisted.getCheckStatus());
        assertTrue(persisted.getHasDrift(), "Snapshot column name differs from runtime — drift expected");
        assertNotNull(persisted.getLastCheckedTime());

        List<ModelChangesDTO> storedDrift = JsonUtils.jsonNodeToObject(
                persisted.getDriftContent(), new TypeReference<>() {});
        assertEquals(1, storedDrift.size());
        ModelChangesDTO diff = storedDrift.getFirst();
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

    @Test
    @SuppressWarnings("unchecked")
    void refreshDriftCapturesFailureWithoutClearingPreviousDrift() {
        DesignAppEnvServiceImpl service = Mockito.spy(new DesignAppEnvServiceImpl());
        DesignAppEnvSnapshotService snapshotService = mock(DesignAppEnvSnapshotService.class);
        DesignAppEnvDriftService driftService = mock(DesignAppEnvDriftService.class);
        RemoteApiClient remoteApiClient = mock(RemoteApiClient.class);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "driftService", driftService);
        ReflectionTestUtils.setField(service, "remoteApiClient", remoteApiClient);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(2L);
        appEnv.setAppId(100L);
        appEnv.setEnvType(DesignAppEnvType.PROD);
        doReturn(Optional.of(appEnv)).when(service).getById(2L);

        DesignAppEnvSnapshot snapshot = new DesignAppEnvSnapshot();
        snapshot.setEnvId(2L);
        snapshot.setAppId(100L);
        snapshot.setSnapshot(JsonUtils.objectToJsonNode(Map.of()));
        // Snapshot lookup succeeds for both refreshDrift's snapshot read and compareDesignWithRuntime's drift read.
        // We stub driftService.searchOne (used inside compareDesignWithRuntime) to return an empty Optional
        // so the fallback "previous drift" is empty.
        when(snapshotService.searchOne(any(FlexQuery.class))).thenReturn(Optional.of(snapshot));
        when(driftService.searchOne(any(FlexQuery.class))).thenReturn(Optional.empty());
        // Runtime unreachable — the drift fan-out must surface the failure to the cache.
        when(remoteApiClient.fetchRuntimeMetadata(any(), Mockito.anyString()))
                .thenThrow(new RuntimeException("connect timeout"));

        Set<String> compareFields = Set.of(ModelConstant.ID);
        doReturn(compareFields).when(service).getComparableFields(Mockito.anyString(), Mockito.anyString());

        service.refreshDrift(2L);

        ArgumentCaptor<List<DesignAppEnvDrift>> captor = ArgumentCaptor.forClass(List.class);
        verify(driftService).createOrUpdate(captor.capture(), anyList());
        DesignAppEnvDrift persisted = captor.getValue().getFirst();
        assertEquals(DesignDriftCheckStatus.FAILURE, persisted.getCheckStatus());
        assertTrue(persisted.getErrorMessage().contains("connect timeout"));
        // No previous drift and the check failed, so driftContent must reflect "no known drift"
        // (empty list) rather than null — the API reader treats both uniformly but the column
        // contract says we always store the known-drift snapshot.
        List<ModelChangesDTO> storedDrift = JsonUtils.jsonNodeToObject(
                persisted.getDriftContent(), new TypeReference<>() {});
        assertTrue(storedDrift.isEmpty());
        assertFalse(persisted.getHasDrift());
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshDriftTreatsMissingSnapshotAsEmptyBaselineSoFirstImportSeesRuntimeRows() {
        // Regression: when an env has never been deployed (no snapshot row), refreshDrift
        // used to short-circuit and write an empty drift, which broke the importFromRuntime
        // first-time path. It must instead diff against an empty baseline so every runtime
        // row appears as a deletedRow (runtime-only), giving applyDrift something to invert
        // back onto the design side.
        DesignAppEnvServiceImpl service = Mockito.spy(new DesignAppEnvServiceImpl());
        DesignAppEnvSnapshotService snapshotService = mock(DesignAppEnvSnapshotService.class);
        DesignAppEnvDriftService driftService = mock(DesignAppEnvDriftService.class);
        RemoteApiClient remoteApiClient = mock(RemoteApiClient.class);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "driftService", driftService);
        ReflectionTestUtils.setField(service, "remoteApiClient", remoteApiClient);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(3L);
        appEnv.setAppId(100L);
        appEnv.setEnvType(DesignAppEnvType.DEV);
        doReturn(Optional.of(appEnv)).when(service).getById(3L);

        when(snapshotService.searchOne(any(FlexQuery.class))).thenReturn(Optional.empty());
        when(remoteApiClient.fetchRuntimeMetadata(any(), Mockito.anyString())).thenReturn(List.of());
        when(remoteApiClient.fetchRuntimeMetadata(any(), eq("SysField"))).thenReturn(List.of(Map.of(
                ModelConstant.ID, 42L,
                "modelName", "Account",
                "fieldName", "name",
                "columnName", "name")));

        Set<String> compareFields = Set.of("modelName", "fieldName", "columnName");
        doReturn(compareFields).when(service).getComparableFields(Mockito.anyString(), Mockito.anyString());

        service.refreshDrift(3L);

        ArgumentCaptor<List<DesignAppEnvDrift>> captor = ArgumentCaptor.forClass(List.class);
        verify(driftService).createOrUpdate(captor.capture(), anyList());
        DesignAppEnvDrift persisted = captor.getValue().getFirst();
        assertEquals(DesignDriftCheckStatus.SUCCESS, persisted.getCheckStatus());
        assertTrue(persisted.getHasDrift(),
                "Empty snapshot vs non-empty runtime must register as drift, not as 'no drift'");

        List<ModelChangesDTO> storedDrift = JsonUtils.jsonNodeToObject(
                persisted.getDriftContent(), new TypeReference<>() {});
        assertEquals(1, storedDrift.size());
        ModelChangesDTO diff = storedDrift.getFirst();
        assertTrue(diff.getCreatedRows().isEmpty());
        assertTrue(diff.getUpdatedRows().isEmpty());
        assertEquals(1, diff.getDeletedRows().size(),
                "Runtime row absent from snapshot must surface as a deletedRow so applyDrift can CREATE it on design.");
        RowChangeDTO runtimeRow = diff.getDeletedRows().getFirst();
        assertEquals(42L, runtimeRow.getRowId());
        assertEquals("name", runtimeRow.getCurrentData().get("fieldName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyDriftIsNoOpWhenDriftIsEmpty() {
        DesignAppEnvServiceImpl service = Mockito.spy(new DesignAppEnvServiceImpl());
        DesignAppEnvSnapshotService snapshotService = mock(DesignAppEnvSnapshotService.class);
        DesignAppEnvDriftService driftService = mock(DesignAppEnvDriftService.class);
        ModelService<Serializable> modelService = mock(ModelService.class);
        DesignAppVersionService appVersionService = mock(DesignAppVersionService.class);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "driftService", driftService);
        ReflectionTestUtils.setField(service, "remoteApiClient", mock(RemoteApiClient.class));
        ReflectionTestUtils.setField(service, "modelService", modelService);
        ReflectionTestUtils.setField(service, "appVersionService", appVersionService);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));
        doReturn(List.<ModelChangesDTO>of()).when(service).compareDesignWithRuntime(1L);

        service.applyDrift(1L, true);

        // Mutex acquired (STABLE → IMPORTING) and released (IMPORTING → STABLE) via two updateOne calls;
        // no design writes, no version, no snapshot, no drift clear.
        verify(service, times(2)).updateOne(any(DesignAppEnv.class));
        verify(modelService, never()).createList(Mockito.anyString(), Mockito.anyList());
        verify(modelService, never()).updateList(Mockito.anyString(), Mockito.anyList());
        verify(modelService, never()).deleteByIds(Mockito.anyString(), Mockito.anyList());
        verify(appVersionService, never()).createOne(any(DesignAppVersion.class));
        verify(snapshotService, never()).createOrUpdate(anyList(), anyList());
        verify(driftService, never()).createOrUpdate(anyList(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyDriftInvertsRuntimeOntoDesignAndAdvancesSyntheticVersion() {
        DesignAppEnvServiceImpl service = Mockito.spy(new DesignAppEnvServiceImpl());
        DesignAppEnvSnapshotService snapshotService = mock(DesignAppEnvSnapshotService.class);
        DesignAppEnvDriftService driftService = mock(DesignAppEnvDriftService.class);
        ModelService<Serializable> modelService = mock(ModelService.class);
        DesignAppVersionService appVersionService = mock(DesignAppVersionService.class);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "driftService", driftService);
        ReflectionTestUtils.setField(service, "remoteApiClient", mock(RemoteApiClient.class));
        ReflectionTestUtils.setField(service, "modelService", modelService);
        ReflectionTestUtils.setField(service, "appVersionService", appVersionService);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvType(DesignAppEnvType.DEV);
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));
        when(appVersionService.createOne(any(DesignAppVersion.class))).thenReturn(9001L);

        // Baseline snapshot — has one existing "DesignField" row so the createdRow (snapshot-only)
        // delete path has something visible in the post-import snapshot.
        DesignAppEnvSnapshot baselineSnapshot = new DesignAppEnvSnapshot();
        baselineSnapshot.setAppId(100L);
        baselineSnapshot.setEnvId(1L);
        baselineSnapshot.setDeploymentId(500L);
        baselineSnapshot.setSnapshot(JsonUtils.objectToJsonNode(Map.of(
                "DesignField", List.of(Map.of(
                        ModelConstant.ID, 30L,
                        "fieldName", "onlyInSnapshot")))));
        when(snapshotService.searchOne(any(FlexQuery.class))).thenReturn(Optional.of(baselineSnapshot));

        // Assemble drift with one of each row-change flavour to prove the inversion.
        ModelChangesDTO changes = new ModelChangesDTO("DesignField");

        RowChangeDTO runtimeOnly = new RowChangeDTO("DesignField", 10L);
        runtimeOnly.setAccessType(AccessType.DELETE);
        runtimeOnly.setCurrentData(Map.of(ModelConstant.ID, 10L, "fieldName", "fromRuntime"));
        changes.addDeletedRow(runtimeOnly);

        RowChangeDTO updatedRow = new RowChangeDTO("DesignField", 20L);
        updatedRow.setAccessType(AccessType.UPDATE);
        updatedRow.setDataBeforeChange(Map.of("fieldName", "runtimeName"));
        updatedRow.setDataAfterChange(Map.of("fieldName", "snapshotName"));
        updatedRow.setCurrentData(Map.of(ModelConstant.ID, 20L, "fieldName", "snapshotName"));
        changes.addUpdatedRow(updatedRow);

        RowChangeDTO snapshotOnly = new RowChangeDTO("DesignField", 30L);
        snapshotOnly.setAccessType(AccessType.CREATE);
        snapshotOnly.setCurrentData(Map.of(ModelConstant.ID, 30L, "fieldName", "onlyInSnapshot"));
        changes.addCreatedRow(snapshotOnly);

        doReturn(List.of(changes)).when(service).compareDesignWithRuntime(1L);

        service.applyDrift(1L, true);

        // CREATE on Design uses the runtime currentData (the row that was runtime-only).
        ArgumentCaptor<List<Map<String, Object>>> createCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelService).createList(eq("DesignField"), createCaptor.capture());
        assertEquals(1, createCaptor.getValue().size());
        assertEquals(10L, ((Number) createCaptor.getValue().getFirst().get(ModelConstant.ID)).longValue());
        assertEquals("fromRuntime", createCaptor.getValue().getFirst().get("fieldName"));

        // UPDATE on Design uses the dataBeforeChange (runtime values) + rowId.
        ArgumentCaptor<List<Map<String, Object>>> updateCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelService).updateList(eq("DesignField"), updateCaptor.capture());
        assertEquals(1, updateCaptor.getValue().size());
        Map<String, Object> updatePayload = updateCaptor.getValue().getFirst();
        assertEquals(20L, ((Number) updatePayload.get(ModelConstant.ID)).longValue());
        assertEquals("runtimeName", updatePayload.get("fieldName"));

        // DELETE on Design by rowId for the snapshot-only row.
        ArgumentCaptor<List<Serializable>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelService).deleteByIds(eq("DesignField"), deleteCaptor.capture());
        assertEquals(List.of(30L), deleteCaptor.getValue());

        // Synthetic FROZEN version minted and id propagated to env.currentVersionId.
        ArgumentCaptor<DesignAppVersion> versionCaptor = ArgumentCaptor.forClass(DesignAppVersion.class);
        verify(appVersionService).createOne(versionCaptor.capture());
        DesignAppVersion synthetic = versionCaptor.getValue();
        assertEquals(100L, synthetic.getAppId());
        assertEquals(DesignAppVersionStatus.FROZEN, synthetic.getStatus());
        assertTrue(synthetic.getName().startsWith("imported-from-runtime-"),
                "Synthetic version name must carry the timestamp prefix; got " + synthetic.getName());
        assertEquals(9001L, appEnv.getCurrentVersionId());

        // Post-import snapshot row keyed by the synthetic version id in the deploymentId slot.
        ArgumentCaptor<List<DesignAppEnvSnapshot>> snapshotCaptor = ArgumentCaptor.forClass(List.class);
        verify(snapshotService).createOrUpdate(snapshotCaptor.capture(), anyList());
        DesignAppEnvSnapshot written = snapshotCaptor.getValue().getFirst();
        assertEquals(100L, written.getAppId());
        assertEquals(1L, written.getEnvId());
        assertEquals(9001L, written.getDeploymentId(),
                "Synthetic version id must double as the snapshot's deploymentId (CosID-unique).");
        Map<String, List<Map<String, Object>>> writtenData = JsonUtils.jsonNodeToObject(
                written.getSnapshot(), new TypeReference<>() {});
        List<Map<String, Object>> rows = writtenData.get("DesignField");
        assertEquals(2, rows.size());
        Set<Long> rowIds = Set.of(
                ((Number) rows.get(0).get(ModelConstant.ID)).longValue(),
                ((Number) rows.get(1).get(ModelConstant.ID)).longValue());
        assertEquals(Set.of(10L, 20L), rowIds, "Snapshot must reflect post-import state: runtime row added, snapshot-only row dropped.");

        // Drift cache cleared (empty content, SUCCESS, hasDrift=false) to prevent re-apply.
        ArgumentCaptor<List<DesignAppEnvDrift>> driftCaptor = ArgumentCaptor.forClass(List.class);
        verify(driftService).createOrUpdate(driftCaptor.capture(), anyList());
        DesignAppEnvDrift cleared = driftCaptor.getValue().getFirst();
        assertEquals(DesignDriftCheckStatus.SUCCESS, cleared.getCheckStatus());
        assertFalse(cleared.getHasDrift());
        List<ModelChangesDTO> clearedContent = JsonUtils.jsonNodeToObject(
                cleared.getDriftContent(), new TypeReference<>() {});
        assertTrue(clearedContent.isEmpty());

        // Mutex acquired + released via two updateOne calls (STABLE → IMPORTING → STABLE),
        // plus the env update that propagates currentVersionId — three total.
        verify(service, times(3)).updateOne(any(DesignAppEnv.class));
        verify(service, never()).refreshDrift(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyDriftWithUseCachedFalseRefreshesFirst() {
        DesignAppEnvServiceImpl service = Mockito.spy(new DesignAppEnvServiceImpl());
        DesignAppEnvSnapshotService snapshotService = mock(DesignAppEnvSnapshotService.class);
        DesignAppEnvDriftService driftService = mock(DesignAppEnvDriftService.class);
        ModelService<Serializable> modelService = mock(ModelService.class);
        DesignAppVersionService appVersionService = mock(DesignAppVersionService.class);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "driftService", driftService);
        ReflectionTestUtils.setField(service, "remoteApiClient", mock(RemoteApiClient.class));
        ReflectionTestUtils.setField(service, "modelService", modelService);
        ReflectionTestUtils.setField(service, "appVersionService", appVersionService);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(7L);
        appEnv.setAppId(100L);
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(7L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));
        // Skip the real refreshDrift — its behaviour is covered by dedicated tests.
        Mockito.doNothing().when(service).refreshDrift(7L);
        // After the refresh, report no drift so applyDrift short-circuits before the writes.
        doReturn(List.<ModelChangesDTO>of()).when(service).compareDesignWithRuntime(7L);

        service.applyDrift(7L, false);

        verify(service).refreshDrift(7L);
        verify(modelService, never()).createList(Mockito.anyString(), Mockito.anyList());
        verify(appVersionService, never()).createOne(any(DesignAppVersion.class));
    }
}
