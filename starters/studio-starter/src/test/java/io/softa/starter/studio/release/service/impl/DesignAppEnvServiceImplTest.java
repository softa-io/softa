package io.softa.starter.studio.release.service.impl;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.VersionException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.studio.release.connector.Connector;
import io.softa.starter.studio.release.connector.ConnectorFactory;
import io.softa.starter.studio.release.desired.*;
import io.softa.starter.studio.release.dto.*;
import io.softa.starter.studio.release.entity.DesignActivity;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.entity.DesignSnapshot;
import io.softa.starter.studio.release.enums.*;
import io.softa.starter.studio.release.service.DesignActivityService;
import io.softa.starter.studio.release.service.DesignAppService;
import io.softa.starter.studio.release.service.DesignSnapshotService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DesignAppEnvServiceImplTest {

    @BeforeAll
    static void registerIdGenerator() {
        // applyDrift's import path mints fresh design ids via CosID (stampImportedRow); register a
        // deterministic generator so unit tests don't depend on a configured CosID share.
        AtomicLong counter = new AtomicLong(900_000L);
        DefaultIdGeneratorProvider.INSTANCE.setShare(counter::incrementAndGet);
    }

    /**
     * A checksum-gate result whose Model delta classifies the given aggregates (the
     * converger drives the selective fetch + restriction from this classification, so it must name the
     * real aggregates under test, not a sentinel). OptionSets empty.
     */
    private static DesiredStateComparator.Result modelDelta(Set<String> onlyInDesign,
                                                            Set<String> differing, Set<String> onlyInRuntime) {
        AggregateChecksumDiff.Delta models =
                new AggregateChecksumDiff.Delta(onlyInDesign, differing, onlyInRuntime, Set.of());
        AggregateChecksumDiff.Delta empty =
                new AggregateChecksumDiff.Delta(Set.of(), Set.of(), Set.of(), Set.of());
        return new DesiredStateComparator.Result(models, empty);
    }

    /** A checksum-gate result that reports the env already in sync (publish/drift short-circuits). */
    private static DesiredStateComparator.Result inSync() {
        AggregateChecksumDiff.Delta empty = new AggregateChecksumDiff.Delta(
                Set.of(), Set.of(), Set.of(), Set.of());
        return new DesiredStateComparator.Result(empty, empty);
    }

    // ------------------------------------------------------------------ fixture
    // Constructor-injected collaborators — JUnit creates a fresh test instance (and thus fresh mocks)
    // per test method. The converger is real over a mocked checksum comparator, and the differ / drift
    // importer are real (pure logic, the importer backed by the same mocked ModelService), matching
    // production wiring. Raw ModelService on purpose: the generic delete/create APIs accept List<Long>
    // env ids without inference noise.
    private final DesignAppService appService = mock(DesignAppService.class);
    private final ModelService modelService = mock(ModelService.class);
    private final DesignEnvCloner envCloner = mock(DesignEnvCloner.class);
    private final DesignEnvSource envSource = mock(DesignEnvSource.class);
    private final DesiredStateComparator comparator = mock(DesiredStateComparator.class);
    private final DesiredStateDeployService deployService = mock(DesiredStateDeployService.class);
    private final ConnectorFactory connectorFactory = mock(ConnectorFactory.class);
    private final DesignEnvMerger envMerger = mock(DesignEnvMerger.class);
    private final DesignActivityService activityService = mock(DesignActivityService.class);
    private final DesignSnapshotService snapshotService = mock(DesignSnapshotService.class);

    /** A spy over a fully constructor-wired service — tests stub the inherited getById/updateOne on the spy. */
    private DesignAppEnvServiceImpl newService() {
        return Mockito.spy(new DesignAppEnvServiceImpl(appService, modelService, envCloner, envSource,
                new DesiredStateConverger(comparator, new DesignAggregateDiffer()),
                deployService, connectorFactory, envMerger, activityService, snapshotService,
                new DesignAggregateDiffer(), new DesignDriftImporter(modelService)));
    }

    @Test
    void compareDesignWithRuntimeReturnsUpdateWhenRuntimeColumnDiffers() {
        // Drift computed on demand (no cache). Same business key Account.customerName,
        // differing columnName → UPDATE in the returned diff.
        DesignAppEnvServiceImpl service = newService();
        Connector connector = mock(Connector.class);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        when(appService.getFieldValue(eq(100L), any())).thenReturn("demo-app");
        when(connectorFactory.forEnv(appEnv)).thenReturn(connector);
        // Account is present both sides with a differing checksum → the converger fetches it selectively.
        when(comparator.compare(any(Connector.class), eq("demo-app"), any()))
                .thenReturn(modelDelta(Set.of(), Set.of("Account"), Set.of()));

        DesignRows design = new DesignRows(
                List.of(Map.of(ModelConstant.ID, 10L, "modelName", "Account")),
                List.of(Map.of(ModelConstant.ID, 11L, "modelName", "Account",
                        "fieldName", "customerName", "columnName", "customer_name")),
                List.of(), List.of(), List.of());
        when(envSource.load(100L, 1L)).thenReturn(design);
        DesignRows runtime = new DesignRows(
                List.of(Map.of(ModelConstant.ID, 900L, "modelName", "Account")),
                List.of(Map.of(ModelConstant.ID, 990L, "modelName", "Account",
                        "fieldName", "customerName", "columnName", "name")),
                List.of(), List.of(), List.of());
        when(connector.readSchema(eq("demo-app"), any())).thenReturn(runtime);

        List<RowChangeDTO> drift = service.compareDesignWithRuntime(1L);

        ModelChangesDTO diff = DesignMetaTables.group(drift).stream()
                .filter(c -> "DesignField".equals(c.getModelName())).findFirst().orElseThrow();
        assertTrue(diff.getCreatedRows().isEmpty());
        assertTrue(diff.getDeletedRows().isEmpty());
        assertEquals(1, diff.getUpdatedRows().size());
        RowChangeDTO updatedRow = diff.getUpdatedRows().getFirst();
        // direction design→runtime: previous = runtime value, fullRow = design value.
        assertEquals("name", updatedRow.getPreviousValuesForChangedFields().get("columnName"));
        assertEquals("customer_name", updatedRow.getFullRow().get("columnName"));
        assertEquals("customerName", updatedRow.getFullRow().get("fieldName"));
    }

    @Test
    void getDriftEnvelopeReportsFailureWhenRuntimeUnreachable() {
        // On-demand drift. A runtime-unreachable fetch surfaces as a FAILURE envelope (the UI
        // shows the error) rather than propagating the exception.
        DesignAppEnvServiceImpl service = newService();
        Connector connector = mock(Connector.class);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(2L);
        appEnv.setAppId(100L);
        doReturn(Optional.of(appEnv)).when(service).getById(2L);
        when(appService.getFieldValue(eq(100L), any())).thenReturn("demo-app");
        when(envSource.load(100L, 2L)).thenReturn(new DesignRows(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(connectorFactory.forEnv(appEnv)).thenReturn(connector);
        // A runtime-only aggregate forces a selective fetch — which then fails (connection down).
        when(comparator.compare(any(Connector.class), eq("demo-app"), any()))
                .thenReturn(modelDelta(Set.of(), Set.of(), Set.of("Account")));
        when(connector.readSchema(anyString(), any())).thenThrow(new RuntimeException("connect timeout"));

        DriftEnvelopeDTO envelope = service.getDriftEnvelope(2L);

        assertEquals(DesignDriftCheckStatus.FAILURE, envelope.getCheckStatus());
        assertTrue(envelope.getErrorMessage().contains("connect timeout"));
        assertFalse(envelope.isHasDrift());
    }

    @Test
    void compareDesignWithRuntimeTreatsEmptyDesignAsRuntimeOnlyRows() {
        // First-import path: the env's design is empty (never published). Every runtime row then
        // surfaces as a deletedRow (runtime-only), giving applyDrift something to invert onto design.
        DesignAppEnvServiceImpl service = newService();
        Connector connector = mock(Connector.class);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(3L);
        appEnv.setAppId(100L);
        doReturn(Optional.of(appEnv)).when(service).getById(3L);
        when(appService.getFieldValue(eq(100L), any())).thenReturn("demo-app");
        when(connectorFactory.forEnv(appEnv)).thenReturn(connector);
        // Empty design + a runtime Account → Account is runtime-only (fetched, then deleted in the diff).
        when(comparator.compare(any(Connector.class), eq("demo-app"), any()))
                .thenReturn(modelDelta(Set.of(), Set.of(), Set.of("Account")));
        when(envSource.load(100L, 3L)).thenReturn(new DesignRows(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        DesignRows runtime = new DesignRows(
                List.of(Map.of(ModelConstant.ID, 40L, "modelName", "Account")),
                List.of(Map.of(ModelConstant.ID, 42L, "modelName", "Account",
                        "fieldName", "name", "columnName", "name")),
                List.of(), List.of(), List.of());
        when(connector.readSchema(eq("demo-app"), any())).thenReturn(runtime);

        List<RowChangeDTO> drift = service.compareDesignWithRuntime(3L);

        ModelChangesDTO diff = DesignMetaTables.group(drift).stream()
                .filter(c -> "DesignField".equals(c.getModelName())).findFirst().orElseThrow();
        assertTrue(diff.getCreatedRows().isEmpty());
        assertTrue(diff.getUpdatedRows().isEmpty());
        assertEquals(1, diff.getDeletedRows().size(),
                "runtime-only field → deletedRow so applyDrift can CREATE it on design");
        assertEquals("name", diff.getDeletedRows().getFirst().getFullRow().get("fieldName"));
    }

    @Test
    void publishOrchestratesActivityRecordAndConverge() {
        // publish(envId) now orchestrates inline — CAS-acquire the env mutex, open a PUBLISH
        // DesignActivity, run publishInternal, mark it SUCCESS, release the mutex.
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));

        DesignActivity activity = new DesignActivity();
        activity.setId(77L);
        when(activityService.start(eq(100L), eq(1L), eq(DesignActivityKind.PUBLISH), isNull(), any()))
                .thenReturn(activity);
        DesignAppEnvServiceImpl.PublishResult result =
                new DesignAppEnvServiceImpl.PublishResult(List.of(), "CREATE TABLE orders (id BIGINT);");
        doReturn(result).when(service).publishInternal(appEnv);
        // The post-publish design snapshot is captured + linked on succeed.
        when(envSource.load(100L, 1L)).thenReturn(new DesignRows(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(activityService.snapshot(eq(77L), any())).thenReturn(99L);

        service.publish(1L);

        verify(service, atLeastOnce()).updateOne(any(DesignAppEnv.class));   // mutex acquire (version CAS)
        verify(activityService).start(eq(100L), eq(1L), eq(DesignActivityKind.PUBLISH), isNull(), any());
        verify(activityService).succeed(eq(77L), any(), any(), eq(99L));
    }

    @Test
    void acquireEnvLockRefusesWhenVersionCasIsLost() {
        // The env-status mutex is an optimistic version CAS: if a concurrent op already bumped the version,
        // this acquire's updateOne matches 0 rows → VersionException → translated to a "busy" refusal.
        // The lock is NOT acquired, so no activity is opened and nothing is published.
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setName("dev");
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);   // looked free at read time...
        appEnv.setVersion(5L);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        // ...but the version was superseded by a racing acquire → the guarded UPDATE throws.
        doThrow(new VersionException("version superseded")).when(service).updateOne(any(DesignAppEnv.class));

        assertThrows(IllegalArgumentException.class, () -> service.publish(1L));
        verify(activityService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void acquireEnvLockRefusesWhenEnvAlreadyBusy() {
        // Pre-check: an env not in STABLE (e.g. a stuck DEPLOYING lock) is refused before the CAS is even
        // attempted — updateOne is never called.
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setName("dev");
        appEnv.setEnvStatus(DesignAppEnvStatus.DEPLOYING);   // already held
        appEnv.setVersion(5L);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);

        assertThrows(IllegalArgumentException.class, () -> service.publish(1L));
        verify(service, never()).updateOne(any(DesignAppEnv.class));
        verify(activityService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void restoreOverwritesDesignFromSnapshotThenPublishes() {
        // restore(activityId) replays the activity's captured design onto the env
        // (overwrite under the mutex), then publishes to converge the runtime.
        DesignAppEnvServiceImpl service = newService();

        DesignActivity activity = new DesignActivity();
        activity.setId(50L);
        activity.setEnvId(1L);
        activity.setKind(DesignActivityKind.PUBLISH);
        activity.setStatus(DesignActivityStatus.SUCCESS);
        activity.setSnapshotId(99L);
        when(activityService.getById(50L)).thenReturn(Optional.of(activity));

        DesignRows captured = new DesignRows(
                List.of(Map.of(ModelConstant.ID, 7L, "modelName", "Account")),
                List.of(), List.of(), List.of(), List.of());
        DesignSnapshot snapshot = new DesignSnapshot();
        snapshot.setId(99L);
        snapshot.setContent(JsonUtils.objectToJsonNode(captured));
        when(snapshotService.getById(99L)).thenReturn(Optional.of(snapshot));

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));
        doNothing().when(service).publish(1L);

        service.restore(50L);

        // Design overwritten from the snapshot (deserialized back to DesignRows) under the env mutex...
        ArgumentCaptor<DesignRows> captor = ArgumentCaptor.forClass(DesignRows.class);
        verify(envCloner).replaceEnvDesign(eq(1L), captor.capture());
        assertEquals(1, captor.getValue().models().size());
        assertEquals("Account", captor.getValue().models().getFirst().get("modelName"));
        verify(service, atLeastOnce()).updateOne(any(DesignAppEnv.class));   // mutex acquire (version CAS)
        verify(service, atLeastOnce()).updateOne(any(DesignAppEnv.class));            // mutex release
        // ...then the runtime converged via publish.
        verify(service).publish(1L);
    }

    @Test
    void restoreRejectsActivityWithoutSnapshot() {
        DesignAppEnvServiceImpl service = newService();

        DesignActivity activity = new DesignActivity();
        activity.setId(60L);
        activity.setEnvId(1L);
        activity.setKind(DesignActivityKind.PUBLISH);
        activity.setStatus(DesignActivityStatus.SUCCESS);
        activity.setSnapshotId(null);   // succeeded PUBLISH but (defensively) no snapshot linked — nothing to restore
        when(activityService.getById(60L)).thenReturn(Optional.of(activity));

        assertThrows(IllegalArgumentException.class, () -> service.restore(60L));
        verify(service, never()).publish(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishInternalSkipsFullFetchAndShipsNothingWhenGateReportsInSync() {
        // R5, the primary target: when the checksum gate reports the env in sync,
        // publishInternal must NOT run the five-table runtime fan-out, and applyToRuntime is handed an
        // empty change set (ships nothing). Soundness: inSync ⇒ the business-key row diff is empty too.
        DesignAppEnvServiceImpl service = newService();
        Connector connector = mock(Connector.class);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(8L);
        appEnv.setAppId(100L);
        // An in-sync env has nothing to publish — publishInternal interrupts (throws). The R5
        // checksum gate still short-circuits the five-table fan-out BEFORE that interrupt, and nothing
        // downstream (connector / applyToRuntime) is touched.
        when(appService.getFieldValue(eq(100L), any())).thenReturn("demo-app");
        when(envSource.load(100L, 8L)).thenReturn(new DesignRows(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(connectorFactory.forEnv(appEnv)).thenReturn(connector);
        when(comparator.compare(any(Connector.class), eq("demo-app"), any())).thenReturn(inSync());

        assertThrows(IllegalArgumentException.class, () -> service.publishInternal(appEnv));

        // Gate short-circuits BEFORE any schema read (full or selective) or apply (the connector is built
        // for the gate).
        verify(connector, never()).readSchema(any());
        verify(connector, never()).readSchema(any(), any());
        verify(deployService, never()).applyToRuntime(any(), any(), any(), anyList());
    }

    @Test
    void compareDesignWithRuntimeSkipsFullFetchWhenGateReportsInSync() {
        // R5: the checksum gate fronts the on-demand drift read — an in-sync env returns empty drift
        // straight from the cheap checksum RPC, skipping the five-table fan-out.
        DesignAppEnvServiceImpl service = newService();
        Connector connector = mock(Connector.class);

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(5L);
        appEnv.setAppId(100L);
        doReturn(Optional.of(appEnv)).when(service).getById(5L);
        when(appService.getFieldValue(eq(100L), any())).thenReturn("demo-app");
        when(envSource.load(100L, 5L)).thenReturn(new DesignRows(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(connectorFactory.forEnv(appEnv)).thenReturn(connector);
        when(comparator.compare(any(Connector.class), eq("demo-app"), any())).thenReturn(inSync());

        List<RowChangeDTO> drift = service.compareDesignWithRuntime(5L);

        assertTrue(drift.isEmpty(), "checksum gate reports in sync → empty drift, no fan-out");
        verify(connector, never()).readSchema(any());
        verify(connector, never()).readSchema(any(), any());
    }

    @Test
    void applyDriftIsNoOpWhenDriftIsEmpty() {
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));
        doReturn(List.<RowChangeDTO>of()).when(service).compareDesignWithRuntime(1L);

        service.applyDrift(1L);

        // Mutex acquired (atomic CAS STABLE→IMPORTING) and released (IMPORTING→STABLE via updateOne);
        // no design writes.
        verify(service, atLeastOnce()).updateOne(any(DesignAppEnv.class));   // mutex acquire (version CAS)
        verify(service, atLeastOnce()).updateOne(any(DesignAppEnv.class));
        verify(modelService, never()).createList(Mockito.anyString(), Mockito.anyList());
        verify(modelService, never()).updateList(Mockito.anyString(), Mockito.anyList());
        verify(modelService, never()).deleteByIds(Mockito.anyString(), Mockito.anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyDriftInvertsRuntimeOntoDesign() {
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvType(DesignAppEnvType.DEV);
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));
        // A Softa env import records a kind=IMPORT activity + a post-import snapshot.
        DesignActivity activity = new DesignActivity();
        activity.setId(77L);
        when(activityService.start(eq(100L), eq(1L), eq(DesignActivityKind.IMPORT), isNull(), any()))
                .thenReturn(activity);
        when(envSource.load(100L, 1L)).thenReturn(new DesignRows(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(activityService.snapshot(eq(77L), any())).thenReturn(99L);

        // Import overwrites the env's per-env design rows directly — no snapshot baseline.
        // Assemble drift (flat row list, self-describing via op + table) with one of each
        // row-change flavour to prove the inversion onto design.
        // Import UPDATE/DELETE locate the design row by its BUSINESS KEY (modelName+fieldName),
        // resolving the REAL surrogate id — identity is the business key, there is no logicalId.
        when(modelService.searchList(eq("DesignField"), any())).thenReturn(List.of(
                Map.of(ModelConstant.ID, 2001L, "modelName", "Customer", "fieldName", "name"),
                Map.of(ModelConstant.ID, 3001L, "modelName", "Customer", "fieldName", "legacy")));

        RowChangeDTO runtimeOnly = new RowChangeDTO();   // runtime has, design doesn't → CREATE on design
        runtimeOnly.setOp(RowChangeOp.DELETE);
        runtimeOnly.setTable(MetaTable.FIELD);
        runtimeOnly.setFullRow(Map.of(ModelConstant.ID, 10L, "modelName", "Customer", "fieldName", "fromRuntime"));

        RowChangeDTO updatedRow = new RowChangeDTO();    // content differs → UPDATE design, located by business key
        updatedRow.setOp(RowChangeOp.UPDATE);
        updatedRow.setTable(MetaTable.FIELD);
        updatedRow.setPreviousValuesForChangedFields(Map.of("label", "runtimeLabel"));
        updatedRow.setFullRow(Map.of("modelName", "Customer", "fieldName", "name"));

        RowChangeDTO designOnly = new RowChangeDTO();    // design has, runtime doesn't → DELETE from design
        designOnly.setOp(RowChangeOp.CREATE);
        designOnly.setTable(MetaTable.FIELD);
        designOnly.setFullRow(Map.of("modelName", "Customer", "fieldName", "legacy"));

        doReturn(List.of(runtimeOnly, updatedRow, designOnly)).when(service).compareDesignWithRuntime(1L);

        service.applyDrift(1L);

        // CREATE on Design carries the runtime row's business values, re-stamped with the target env's
        // per-env identity: a fresh design id and target appId/envId — NOT the runtime
        // surrogate id (design and runtime are separate id spaces).
        ArgumentCaptor<List<Map<String, Object>>> createCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelService).createList(eq("DesignField"), createCaptor.capture());
        assertEquals(1, createCaptor.getValue().size());
        Map<String, Object> created = createCaptor.getValue().getFirst();
        assertEquals("fromRuntime", created.get("fieldName"));
        assertEquals(1L, ((Number) created.get("envId")).longValue(), "stamped to the target env");
        assertEquals(100L, ((Number) created.get("appId")).longValue(), "stamped to the target app");
        assertNotNull(created.get(ModelConstant.ID), "imported row is stamped with a fresh design id");
        assertNotEquals(10L, ((Number) created.get(ModelConstant.ID)).longValue(), "runtime surrogate id is not reused as the design id");

        // UPDATE on Design writes the runtime values, located by BUSINESS KEY → the design row's REAL
        // surrogate id (2001), NOT id-as-business-key (identity is the business key).
        ArgumentCaptor<List<Map<String, Object>>> updateCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelService).updateList(eq("DesignField"), updateCaptor.capture());
        assertEquals(1, updateCaptor.getValue().size());
        Map<String, Object> updatePayload = updateCaptor.getValue().getFirst();
        assertEquals(2001L, ((Number) updatePayload.get(ModelConstant.ID)).longValue(),
                "located by business key → the design row's real id");
        assertEquals("runtimeLabel", updatePayload.get("label"));

        // DELETE from Design located by business key → the design-only row's REAL id (3001).
        ArgumentCaptor<List<Serializable>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelService).deleteByIds(eq("DesignField"), deleteCaptor.capture());
        assertEquals(List.of(3001L), deleteCaptor.getValue());

        // Mutex acquired (atomic CAS STABLE→IMPORTING) + released (IMPORTING→STABLE via updateOne).
        verify(service, atLeastOnce()).updateOne(any(DesignAppEnv.class));   // mutex acquire (version CAS)
        verify(service, atLeastOnce()).updateOne(any(DesignAppEnv.class));
        // The import is audited as a kind=IMPORT activity with a post-import snapshot (restorable).
        verify(activityService).succeed(eq(77L), any(), isNull(), eq(99L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyDriftRelinksImportedChildToTargetEnvParent() {
        // A1: an imported runtime field carries modelName (business code) but NO modelId (runtime sys_*
        // dropped the surrogate FK). The import must relink it to the target env's DesignModel that owns
        // that modelName — not leave modelId null (which would orphan it for env↔env merge / FK nav).
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));
        DesignActivity activity = new DesignActivity();
        activity.setId(77L);
        when(activityService.start(eq(100L), eq(1L), eq(DesignActivityKind.IMPORT), isNull(), any()))
                .thenReturn(activity);
        when(envSource.load(100L, 1L)).thenReturn(new DesignRows(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(activityService.snapshot(eq(77L), any())).thenReturn(99L);

        // The target env already holds DesignModel "Account" (id 555) — the parent for the imported field.
        when(modelService.searchList(eq("DesignModel"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of(ModelConstant.ID, 555L, "modelName", "Account")));

        // Drift: a runtime-only field Account.email → DELETE row-change → CREATE on design.
        RowChangeDTO runtimeOnly = new RowChangeDTO();
        runtimeOnly.setOp(RowChangeOp.DELETE);
        runtimeOnly.setTable(MetaTable.FIELD);
        runtimeOnly.setFullRow(Map.of(ModelConstant.ID, 10L, "modelName", "Account", "fieldName", "email"));
        doReturn(List.of(runtimeOnly)).when(service).compareDesignWithRuntime(1L);

        service.applyDrift(1L);

        ArgumentCaptor<List<Map<String, Object>>> createCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelService).createList(eq("DesignField"), createCaptor.capture());
        Map<String, Object> created = createCaptor.getValue().getFirst();
        assertEquals("email", created.get("fieldName"));
        assertEquals(555L, ((Number) created.get("modelId")).longValue(),
                "imported field relinked to the target env's Account model id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reverseRecordsReverseKindAndNeverDeletesDesignOptionSets() {
        // Option-set data-loss guard: a JDBC (physical) reverse reports NO option sets. The design's option
        // sets must NOT be deleted as "design-only", and the activity is recorded as kind=REVERSE.
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setConnectorType(ConnectorType.JDBC);   // physical source → REVERSE, option sets guarded
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));
        DesignActivity activity = new DesignActivity();
        activity.setId(88L);
        when(activityService.start(eq(100L), eq(1L), eq(DesignActivityKind.REVERSE), isNull(), any()))
                .thenReturn(activity);
        when(envSource.load(100L, 1L)).thenReturn(new DesignRows(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(activityService.snapshot(eq(88L), any())).thenReturn(99L);

        // Drift: a design-only MODEL (→ DELETE on reverse) + a design-only OPTION_SET (→ MUST be guarded).
        RowChangeDTO modelOnly = new RowChangeDTO();
        modelOnly.setOp(RowChangeOp.CREATE);
        modelOnly.setTable(MetaTable.MODEL);
        modelOnly.setFullRow(Map.of(ModelConstant.ID, 40L, "modelName", "Legacy"));
        RowChangeDTO optionSetOnly = new RowChangeDTO();
        optionSetOnly.setOp(RowChangeOp.CREATE);
        optionSetOnly.setTable(MetaTable.OPTION_SET);
        optionSetOnly.setFullRow(Map.of(ModelConstant.ID, 50L, "optionSetCode", "status"));
        doReturn(List.of(modelOnly, optionSetOnly)).when(service).compareDesignWithRuntime(1L);
        // The env's existing design model — DELETE-from-design locates it by business key.
        when(modelService.searchList(eq("DesignModel"), any())).thenReturn(List.of(
                Map.of(ModelConstant.ID, 4001L, "modelName", "Legacy")));

        service.applyDrift(1L);

        // The design-only MODEL is removed (reverse syncs design to the physical structure), located by
        // business key → its real surrogate id (4001)...
        ArgumentCaptor<List<Serializable>> modelDeleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelService).deleteByIds(eq("DesignModel"), modelDeleteCaptor.capture());
        assertEquals(List.of(4001L), modelDeleteCaptor.getValue());
        // ...but the design's OPTION SETS are NEVER deleted (a physical source cannot observe them).
        verify(modelService, never()).deleteByIds(eq("DesignOptionSet"), any());
        // Audited as REVERSE with a restorable snapshot.
        verify(activityService).succeed(eq(88L), any(), isNull(), eq(99L));
    }

    @Test
    void importActivityIsRestorable() {
        // An IMPORT (like REVERSE / PUBLISH / MERGE) captures a snapshot, so it is
        // restorable — restore no longer restricts to PUBLISH / MERGE.
        DesignAppEnvServiceImpl service = newService();

        DesignActivity activity = new DesignActivity();
        activity.setId(70L);
        activity.setEnvId(1L);
        activity.setKind(DesignActivityKind.IMPORT);
        activity.setStatus(DesignActivityStatus.SUCCESS);
        activity.setSnapshotId(99L);
        when(activityService.getById(70L)).thenReturn(Optional.of(activity));
        DesignSnapshot snapshot = new DesignSnapshot();
        snapshot.setId(99L);
        snapshot.setContent(JsonUtils.objectToJsonNode(new DesignRows(
                List.of(), List.of(), List.of(), List.of(), List.of())));
        when(snapshotService.getById(99L)).thenReturn(Optional.of(snapshot));

        DesignAppEnv appEnv = new DesignAppEnv();
        appEnv.setId(1L);
        appEnv.setAppId(100L);
        appEnv.setEnvStatus(DesignAppEnvStatus.STABLE);
        doReturn(Optional.of(appEnv)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignAppEnv.class));
        doNothing().when(service).publish(1L);

        service.restore(70L);

        verify(envCloner).replaceEnvDesign(eq(1L), any(DesignRows.class));
        verify(service).publish(1L);
    }

    /**
     * Mirror {@code DesignModelServiceImplTest}: deleting an env cascades the full per-env
     * design workspace — DesignField / DesignModelIndex / DesignOptionItem (children) before DesignModel /
     * DesignOptionSet (parents) — each scoped by {@code envId}, then deletes the env row itself. No design
     * row is left with a dangling {@code env_id}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void deleteByIdsCascadesDesignWorkspaceChildrenFirst() {
        DesignAppEnvServiceImpl service = newService();
        // Raw mock so the generic delete API accepts the List<Long> env ids without inference noise.
        // The cascade uses the subclass field; super.deleteByIds uses the shadowed EntityServiceImpl field.
        ReflectionTestUtils.setField(service, EntityServiceImpl.class, "modelService", modelService, ModelService.class);

        List<Long> ids = List.of(4242L);
        DesignAppEnv env = new DesignAppEnv();
        env.setId(4242L);
        env.setName("dev");                       // not protected
        doReturn(List.of(env)).when(service).getByIds(ids);
        when(modelService.deleteByIds(eq("DesignAppEnv"), eq(ids))).thenReturn(true);

        assertTrue(service.deleteByIds(ids));

        // Children drop before parents (DesignAggregate.deleteOrder()), then the env row.
        InOrder inOrder = Mockito.inOrder(modelService);
        ArgumentCaptor<Filters> fieldFilter = ArgumentCaptor.forClass(Filters.class);
        inOrder.verify(modelService).deleteByFilters(eq("DesignOptionItem"), any(Filters.class));
        inOrder.verify(modelService).deleteByFilters(eq("DesignModelIndex"), any(Filters.class));
        inOrder.verify(modelService).deleteByFilters(eq("DesignField"), fieldFilter.capture());
        inOrder.verify(modelService).deleteByFilters(eq("DesignOptionSet"), any(Filters.class));
        inOrder.verify(modelService).deleteByFilters(eq("DesignModel"), any(Filters.class));
        inOrder.verify(modelService).deleteByIds("DesignAppEnv", ids);

        // The cascade is scoped to this env by envId — never an unscoped wipe of another env's rows.
        String rendered = fieldFilter.getValue().toString();
        assertTrue(rendered.contains("envId"), rendered);
        assertTrue(rendered.contains("4242"), rendered);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteByIdFunnelsThroughDeleteByIdsSoSingleEnvAlsoCascades() {
        DesignAppEnvServiceImpl service = newService();
        ReflectionTestUtils.setField(service, EntityServiceImpl.class, "modelService", modelService, ModelService.class);

        DesignAppEnv env = new DesignAppEnv();
        env.setId(5L);
        env.setName("qa");
        doReturn(List.of(env)).when(service).getByIds(List.of(5L));
        when(modelService.deleteByIds(eq("DesignAppEnv"), eq(List.of(5L)))).thenReturn(true);

        assertTrue(service.deleteById(5L));

        verify(modelService).deleteByFilters(eq("DesignField"), any(Filters.class));
        verify(modelService).deleteByFilters(eq("DesignOptionSet"), any(Filters.class));
        verify(modelService).deleteByIds("DesignAppEnv", List.of(5L));
    }

    /**
     * A {@code protectedEnv} env is refused outright (fail-closed): the delete throws before any design
     * row or the env itself is removed, and the message names the offending env so the operator knows to
     * clear the flag first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void deleteByIdsRejectsProtectedEnvAndDeletesNothing() {
        DesignAppEnvServiceImpl service = newService();

        List<Long> ids = List.of(9L);
        DesignAppEnv prod = new DesignAppEnv();
        prod.setId(9L);
        prod.setName("prod");
        prod.setProtectedEnv(true);
        doReturn(List.of(prod)).when(service).getByIds(ids);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteByIds(ids));
        assertTrue(ex.getMessage().contains("prod"), ex.getMessage());

        // Nothing was torn down — neither the design workspace nor the env row.
        verify(modelService, never()).deleteByFilters(anyString(), any(Filters.class));
        verify(modelService, never()).deleteByIds(anyString(), anyList());
    }

    @Test
    void previewRuntimeDriftShowsRuntimeVsLastPublishSnapshot() {
        // Runtime-drift preview = runtime vs the last PUBLISH snapshot (not vs design).
        DesignAppEnvServiceImpl service = newService();
        Connector connector = mock(Connector.class);

        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        env.setAppId(100L);
        doReturn(Optional.of(env)).when(service).getById(1L);
        when(appService.getFieldValue(eq(100L), any())).thenReturn("demo-app");
        when(connectorFactory.forEnv(env)).thenReturn(connector);

        // Base = the last PUBLISH snapshot: Account + field customerName(customer_name).
        DesignRows base = new DesignRows(
                List.of(Map.of(ModelConstant.ID, 10L, "modelName", "Account")),
                List.of(Map.of(ModelConstant.ID, 11L, "modelName", "Account",
                        "fieldName", "customerName", "columnName", "customer_name", "fieldType", "STRING")),
                List.of(), List.of(), List.of());
        DesignActivity publish = new DesignActivity();
        publish.setSnapshotId(500L);
        when(activityService.searchList(any(Filters.class))).thenReturn(List.of(publish));
        DesignSnapshot snapshot = new DesignSnapshot();
        snapshot.setContent(JsonUtils.objectToJsonNode(base));
        when(snapshotService.getById(500L)).thenReturn(Optional.of(snapshot));

        // Runtime drifted out of band: customerName's column renamed customer_name -> name, and a new
        // field `extra` appeared (neither was deployed by studio).
        DesignRows runtime = new DesignRows(
                List.of(Map.of(ModelConstant.ID, 900L, "modelName", "Account")),
                List.of(Map.of(ModelConstant.ID, 990L, "modelName", "Account",
                                "fieldName", "customerName", "columnName", "name", "fieldType", "STRING"),
                        Map.of(ModelConstant.ID, 991L, "modelName", "Account",
                                "fieldName", "extra", "columnName", "extra", "fieldType", "STRING")),
                List.of(), List.of(), List.of());
        when(connector.readSchema("demo-app")).thenReturn(runtime);

        AggregateChangeReport report = service.previewRuntimeDrift(1L);

        assertEquals(1, report.aggregates().size());
        AggregateChangeReport.AggregateChange account = report.aggregates().getFirst();
        assertEquals("Account", account.businessKey());
        assertNull(account.op(), "model row unchanged → only child drift");
        assertEquals(2, account.children().size());

        AggregateChangeReport.ChildChange renamed = account.children().stream()
                .filter(c -> "customerName".equals(c.businessKey())).findFirst().orElseThrow();
        assertEquals(RowChangeOp.UPDATE, renamed.op());
        AggregateChangeReport.AttrChange col = renamed.attrChanges().stream()
                .filter(a -> "columnName".equals(a.attr())).findFirst().orElseThrow();
        assertEquals("customer_name", col.before(), "before = the deployed snapshot value");
        assertEquals("name", col.after(), "after = the current (drifted) runtime value");

        AggregateChangeReport.ChildChange added = account.children().stream()
                .filter(c -> "extra".equals(c.businessKey())).findFirst().orElseThrow();
        assertEquals(RowChangeOp.CREATE, added.op(), "field that appeared on the runtime out of band");
    }

    @Test
    void previewRuntimeDriftEmptyWhenNeverPublished() {
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        env.setAppId(100L);
        doReturn(Optional.of(env)).when(service).getById(1L);
        when(activityService.searchList(any(Filters.class))).thenReturn(List.of());

        AggregateChangeReport report = service.previewRuntimeDrift(1L);
        assertTrue(report.aggregates().isEmpty(), "no last-PUBLISH snapshot → no baseline → empty preview");
    }

    @Test
    void mergeRejectsSameSourceAndTarget() {
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        env.setAppId(100L);
        doReturn(Optional.of(env)).when(service).getById(1L);

        assertThrows(IllegalArgumentException.class, () -> service.merge(1L, 1L, null));
        verify(envMerger, never()).merge(any(), any(), any(), any());
    }

    @Test
    void mergeRejectsCrossAppEnvs() {
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv source = new DesignAppEnv();
        source.setId(1L);
        source.setAppId(100L);
        DesignAppEnv target = new DesignAppEnv();
        target.setId(2L);
        target.setAppId(200L);   // different app — a cross-app merge is refused
        doReturn(Optional.of(source)).when(service).getById(1L);
        doReturn(Optional.of(target)).when(service).getById(2L);

        assertThrows(IllegalArgumentException.class, () -> service.merge(1L, 2L, null));
        verify(envMerger, never()).merge(any(), any(), any(), any());
    }

    @Test
    void seedFromSourceRejectsSameEnv() {
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        env.setAppId(100L);
        doReturn(Optional.of(env)).when(service).getById(1L);

        assertThrows(IllegalArgumentException.class, () -> service.seedFromSource(1L, 1L));
        verify(envCloner, never()).cloneEnv(any(), any(), any());
    }

    @Test
    void seedFromSourceIsIdempotentWhenTargetAlreadyPopulated() {
        // Non-destructive: a target that already owns design rows is never clobbered — returns 0, no clone.
        DesignAppEnvServiceImpl service = newService();

        DesignAppEnv target = new DesignAppEnv();
        target.setId(1L);
        target.setAppId(100L);
        DesignAppEnv source = new DesignAppEnv();
        source.setId(2L);
        source.setAppId(100L);
        doReturn(Optional.of(target)).when(service).getById(1L);
        doReturn(Optional.of(source)).when(service).getById(2L);
        when(envCloner.countModels(100L, 1L)).thenReturn(5);   // target already has design rows

        assertEquals(0, service.seedFromSource(1L, 2L));
        verify(envCloner, never()).cloneEnv(any(), any(), any());
    }
}
