package io.softa.starter.seed.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.seed.dto.FileState;
import io.softa.starter.seed.dto.PlatformLoadResult;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.enums.SeedLoadType;
import io.softa.starter.seed.enums.SeedRecordStatus;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedFileMode;
import io.softa.starter.seed.manifest.SeedManifest;
import io.softa.starter.seed.manifest.SeedManifestReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Platform files load whole, all pending ones or the ones picked, in manifest order and one transaction. An
 * environment's first load initializes it first: the tenant files tenants already have are recorded as run. The
 * files the build owns load at startup wherever they are pending, without initializing, and never from the page.
 */
class PlatformSeedLoaderTest {

    private static final SeedManifest MANIFEST = manifest("""
            platform:
              - data-system/Navigation.json
              - data-system/Permission.json
              - data-platform/MailTemplate.Platform.json
            """);

    /** The access catalogue owned by the build, beside a file the page loads. */
    private static final SeedManifest STARTUP_MANIFEST = manifest("""
            platform:
              - file: data-system/Navigation.json
                mode: startup
              - file: data-system/Permission.json
                mode: startup
              - data-system/Currency.json
            """);

    private SeedCatalog catalog;
    private SeedLoadLog loadLog;
    private SysPreDataService preDataService;
    private CacheService cacheService;
    private TenantSeedRunner tenantRunner;
    private PlatformSeedLoader loader;
    /** The manifest the catalog answers with — held here so a matcher never has to ask the mock for it. */
    private SeedManifest manifest;

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @BeforeEach
    void setUp() {
        catalog = mock(SeedCatalog.class);
        use(MANIFEST);
        when(catalog.checksum(any(SeedFile.class))).thenReturn("v2");
        loadLog = mock(SeedLoadLog.class);
        // An initialized environment; the tests of the first load say otherwise.
        when(loadLog.initialized()).thenReturn(true);
        when(loadLog.platformRecords()).thenReturn(Map.of());
        when(loadLog.start(any(), any(), any())).thenReturn(42L);
        preDataService = mock(SysPreDataService.class);
        cacheService = mock(CacheService.class);
        tenantRunner = mock(TenantSeedRunner.class);
        when(tenantRunner.recordExistingFiles()).thenReturn(recorded(SeedLoadStatus.SUCCEEDED));
        loader = new PlatformSeedLoader(catalog, loadLog, preDataService, cacheService, tenantRunner,
                mock(PlatformTransactionManager.class));
    }

    @Test
    void everyPendingFileLoadsInManifestOrder() {
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        PlatformLoadResult result = loader.load(null);

        assertTrue(result.succeeded());
        assertEquals(List.of("Navigation.json", "Permission.json", "MailTemplate.Platform.json"), result.loadedFiles());
        InOrder inOrder = inOrder(preDataService);
        inOrder.verify(preDataService).loadPreSystemData(List.of("Navigation.json"), LoadMode.OVERWRITE);
        inOrder.verify(preDataService).loadPreSystemData(List.of("Permission.json"), LoadMode.OVERWRITE);
        // The platform tenant's file goes through the platform-tier loader.
        inOrder.verify(preDataService).loadPrePlatformData(List.of("MailTemplate.Platform.json"), LoadMode.OVERWRITE);
        verify(loadLog).start(SeedLoadType.PLATFORM, null, null);
        verify(loadLog).finish(42L, SeedLoadStatus.SUCCEEDED, 3, null);
        // The permission snapshots and entitlements cached from the old rows go.
        verify(cacheService).clearByPrefix("perm:");
        verify(cacheService).clearByPrefix("entl:");
    }

    @Test
    void onlyThePickedFilesLoadAndTheRestStayPending() {
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        PlatformLoadResult result = loader.load(List.of("MailTemplate.Platform.json", "Navigation.json"));

        // Manifest order, whatever order they were picked in.
        assertEquals(List.of("Navigation.json", "MailTemplate.Platform.json"), result.loadedFiles());
        verify(preDataService, never()).loadPreSystemData(eq(List.of("Permission.json")), any());
    }

    @Test
    void aFileThatIsNotPendingIsRefusedBeforeAnythingLoads() {
        pending("Navigation.json");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> loader.load(List.of("Navigation.json", "Permission.json")));

        assertTrue(failure.getMessage().contains("Permission.json"), failure.getMessage());
        verify(loadLog, never()).start(any(), any(), any());
        verify(preDataService, never()).loadPreSystemData(anyList(), any());
    }

    @Test
    void aFileANewerBuildLoadedIsRefusedByName() {
        pending("Navigation.json");
        when(loadLog.state(eq(file("Permission.json")), any())).thenReturn(FileState.LOADED_BY_NEWER_BUILD);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> loader.load(List.of("Navigation.json", "Permission.json")));

        assertTrue(failure.getMessage().contains("Permission.json"), failure.getMessage());
        assertTrue(failure.getMessage().contains("newer build"), failure.getMessage());
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void anEmptyPickLoadsNothingRatherThanEverything() {
        pending("Navigation.json", "Permission.json");

        assertThrows(BusinessException.class, () -> loader.load(List.of()));
        verify(loadLog, never()).start(any(), any(), any());
        verify(preDataService, never()).loadPreSystemData(anyList(), any());
    }

    @Test
    void nothingPendingIsSaidSo() {
        pending();

        assertThrows(BusinessException.class, () -> loader.load(null));
    }

    @Test
    void aFailureNamesTheFileAndLoadsNothing() {
        pending("Navigation.json", "Permission.json");
        doThrow(new IllegalStateException("preIDs [navigation.x] do not exist"))
                .when(preDataService).loadPreSystemData(List.of("Permission.json"), LoadMode.OVERWRITE);

        PlatformLoadResult result = loader.load(null);

        assertFalse(result.succeeded());
        assertEquals("Permission.json", result.failedFile());
        assertTrue(result.errorMessage().contains("do not exist"), result.errorMessage());
        verify(loadLog).record(eq(42L), isNull(), isNull(), eq(file("Permission.json")), eq(SeedRecordStatus.FAILED),
                any(), any());
        verify(loadLog).finish(eq(42L), eq(SeedLoadStatus.FAILED), anyInt(), any());
        verify(cacheService, never()).clearByPrefix(any());
    }

    // ─────────────────────────── the first load initializes ───────────────────────────

    @Test
    void theFirstLoadRecordsTheTenantsAndThenLoads() {
        firstLoad(false);
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        PlatformLoadResult result = loader.load(null);

        // The recording first: it is what initializes the environment.
        InOrder inOrder = inOrder(tenantRunner, preDataService);
        inOrder.verify(tenantRunner).recordExistingFiles();
        inOrder.verify(preDataService).loadPreSystemData(List.of("Navigation.json"), LoadMode.OVERWRITE);
        assertTrue(result.succeeded());
        assertEquals(42L, result.batchId());
        assertEquals(41L, result.initialization().batchId());
        assertEquals(SeedLoadStatus.SUCCEEDED, result.initialization().status());
        assertEquals(12, result.initialization().recordedFiles());
    }

    @Test
    void theFirstLoadWherePlatformDataIsHeldPicksItsFiles() {
        // Every file loaded here would go over what was changed on the pages since the old loader ran.
        firstLoad(true);
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        BusinessException refused = assertThrows(BusinessException.class, () -> loader.load(null));

        assertTrue(refused.getMessage().contains("Pick the files to load"), refused.getMessage());
        verify(tenantRunner, never()).recordExistingFiles();
        verify(preDataService, never()).loadPreSystemData(anyList(), any());
    }

    @Test
    void thePickedFilesLoadOnTheFirstLoadWherePlatformDataIsHeld() {
        firstLoad(true);
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        PlatformLoadResult result = loader.load(List.of("Navigation.json", "Permission.json"));

        verify(tenantRunner).recordExistingFiles();
        assertEquals(List.of("Navigation.json", "Permission.json"), result.loadedFiles());
        verify(preDataService, never()).loadPrePlatformData(anyList(), any());
    }

    @Test
    void aNameThatIsNotPendingIsRefusedBeforeAnythingIsRecorded() {
        firstLoad(true);
        pending("Navigation.json");

        assertThrows(BusinessException.class, () -> loader.load(List.of("Navigaton.json")));

        verify(tenantRunner, never()).recordExistingFiles();
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void aRecordingThatFailsOutrightLoadsNothingSoTheNextLoadTriesAgain() {
        firstLoad(false);
        pending("Navigation.json");
        when(tenantRunner.recordExistingFiles()).thenReturn(recorded(SeedLoadStatus.FAILED));

        PlatformLoadResult result = loader.load(null);

        assertFalse(result.succeeded());
        assertNull(result.batchId());
        assertEquals(SeedLoadStatus.FAILED, result.initialization().status());
        verify(loadLog, never()).start(eq(SeedLoadType.PLATFORM), any(), any());
        verify(preDataService, never()).loadPreSystemData(anyList(), any());
    }

    @Test
    void aLaterLoadRecordsNothing() {
        pending("Navigation.json");

        PlatformLoadResult result = loader.load(null);

        assertNull(result.initialization());
        verify(tenantRunner, never()).recordExistingFiles();
    }

    @Test
    void aTransactionThatCannotStartClosesTheBatchWithItsCause() {
        pending("Navigation.json");
        PlatformTransactionManager unavailable = mock(PlatformTransactionManager.class);
        when(unavailable.getTransaction(any())).thenThrow(new CannotCreateTransactionException("pool exhausted"));
        PlatformSeedLoader noTransaction = new PlatformSeedLoader(catalog, loadLog, preDataService, cacheService,
                tenantRunner, unavailable);

        PlatformLoadResult result = noTransaction.load(null);

        // No file started, so none is blamed — and the batch is closed, not left Running until it reads as stale.
        assertFalse(result.succeeded());
        assertNull(result.failedFile());
        assertTrue(result.errorMessage().contains("pool exhausted"), result.errorMessage());
        verify(loadLog).finish(eq(42L), eq(SeedLoadStatus.FAILED), eq(0), any());
        verify(loadLog, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anEmptyEntryInThePickIsRefused() {
        pending("Navigation.json");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> loader.load(Arrays.asList("Navigation.json", null)));

        assertTrue(failure.getMessage().contains("empty entry"), failure.getMessage());
        verify(loadLog, never()).start(any(), any(), any());
    }

    // ─────────────────────────── the files the build owns ───────────────────────────

    @Test
    void thePendingFilesTheBuildOwnsLoadWithoutInitializing() {
        use(STARTUP_MANIFEST);
        firstLoad(false);
        // Never loaded, changed since: both pending. The page's file is not the startup's business.
        when(loadLog.state(eq(file("Navigation.json")), any())).thenReturn(FileState.NOT_LOADED);
        when(loadLog.state(eq(file("Permission.json")), any())).thenReturn(FileState.CHANGED);
        when(loadLog.state(eq(file("Currency.json")), any())).thenReturn(FileState.NOT_LOADED);

        PlatformLoadResult result = loader.loadStartupFiles().orElseThrow();

        assertTrue(result.succeeded());
        assertEquals(List.of("Navigation.json", "Permission.json"), result.loadedFiles());
        verify(preDataService, never()).loadPreSystemData(eq(List.of("Currency.json")), any());
        // Initializing is the first load from the page, after what the deployment runs once the build is up.
        verify(tenantRunner, never()).recordExistingFiles();
        verify(cacheService).clearByPrefix("perm:");
    }

    @Test
    void aFailedFileTheBuildOwnsLoadsAgain() {
        use(STARTUP_MANIFEST);
        pending("Permission.json");
        when(loadLog.state(eq(file("Permission.json")), any())).thenReturn(FileState.FAILED);

        PlatformLoadResult result = loader.loadStartupFiles().orElseThrow();

        assertEquals(List.of("Permission.json"), result.loadedFiles());
    }

    @Test
    void aBatchLeftRunningDoesNotKeepTheFilesTheBuildOwnsOut() {
        use(STARTUP_MANIFEST);
        pending("Navigation.json", "Permission.json");
        // An instance that stopped part-way left its batch Running; it is not stale yet.
        doThrow(new BusinessException("A seed data operation is still running (batch 7).")).when(loadLog).requireIdle();

        PlatformLoadResult result = loader.loadStartupFiles().orElseThrow();

        assertEquals(List.of("Navigation.json", "Permission.json"), result.loadedFiles());
    }

    @Test
    void aFileTheBuildOwnsThatANewerBuildLoadedIsLeftAlone() {
        // After a rollback: this build's older content does not go over the newer one.
        use(STARTUP_MANIFEST);
        pending("Navigation.json");
        when(loadLog.state(eq(file("Permission.json")), any())).thenReturn(FileState.LOADED_BY_NEWER_BUILD);

        PlatformLoadResult result = loader.loadStartupFiles().orElseThrow();

        assertEquals(List.of("Navigation.json"), result.loadedFiles());
        verify(preDataService, never()).loadPreSystemData(eq(List.of("Permission.json")), any());
    }

    @Test
    void nothingLoadsWhenNoFileTheBuildOwnsIsPending() {
        use(STARTUP_MANIFEST);
        pending("Currency.json");

        assertTrue(loader.pendingStartupFiles().isEmpty());
        assertTrue(loader.loadStartupFiles().isEmpty());
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void thePageLeavesTheFilesTheBuildOwnsOut() {
        use(STARTUP_MANIFEST);
        pending("Navigation.json", "Permission.json", "Currency.json");

        PlatformLoadResult result = loader.load(null);

        assertEquals(List.of("Currency.json"), result.loadedFiles());
        verify(preDataService, never()).loadPreSystemData(eq(List.of("Navigation.json")), any());
    }

    @Test
    void thePageRefusesAFileTheBuildOwnsByName() {
        use(STARTUP_MANIFEST);
        pending("Navigation.json", "Currency.json");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> loader.load(List.of("Navigation.json", "Currency.json")));

        assertTrue(failure.getMessage().contains("Navigation.json"), failure.getMessage());
        assertTrue(failure.getMessage().contains("loads at startup"), failure.getMessage());
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void thePageHasNothingToDoWhenOnlyFilesTheBuildOwnsArePending() {
        use(STARTUP_MANIFEST);
        pending("Navigation.json");

        assertThrows(BusinessException.class, () -> loader.load(null));
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void theStatusSaysWhichFilesTheBuildOwns() {
        use(STARTUP_MANIFEST);
        pending();

        Map<String, SeedFileMode> modes = new java.util.LinkedHashMap<>();
        loader.status().files().forEach(status -> modes.put(status.fileName(), status.mode()));

        assertEquals(Map.of("Navigation.json", SeedFileMode.STARTUP, "Permission.json", SeedFileMode.STARTUP,
                "Currency.json", SeedFileMode.OVERWRITE), modes);
    }

    private static SeedManifest manifest(String yaml) {
        return SeedManifestReader.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test",
                path -> true);
    }

    /** An environment not initialized yet, which held platform seed data before the records, or did not. */
    private void firstLoad(boolean holdsPlatformData) {
        when(loadLog.initialized()).thenReturn(false);
        when(preDataService.exist(any(Filters.class))).thenReturn(holdsPlatformData);
    }

    private static TenantSeedRunner.BatchOutcome recorded(SeedLoadStatus status) {
        return new TenantSeedRunner.BatchOutcome(41L, status, 3, status == SeedLoadStatus.FAILED ? 3 : 0, 12);
    }

    private void use(SeedManifest manifest) {
        this.manifest = manifest;
        when(catalog.manifest()).thenReturn(manifest);
    }

    /** The named platform files of the manifest in use are pending; the others are loaded. */
    private void pending(String... names) {
        List<String> pending = List.of(names);
        for (SeedFile file : manifest.platformFiles()) {
            when(loadLog.state(eq(file), any())).thenReturn(pending.contains(file.file())
                    ? FileState.NOT_LOADED : FileState.LOADED);
        }
    }

    private SeedFile file(String name) {
        return manifest.file(name).orElseThrow();
    }
}
