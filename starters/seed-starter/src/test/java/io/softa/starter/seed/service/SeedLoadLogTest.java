package io.softa.starter.seed.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.seed.dto.FileState;
import io.softa.starter.seed.entity.SeedLoadRecord;
import io.softa.starter.seed.enums.SeedRecordStatus;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedFileMode;
import io.softa.starter.seed.manifest.SeedLevel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether a file runs again is read off its records: never loaded, loaded and unchanged, changed — reloading
 * or not — or failed.
 */
class SeedLoadLogTest {

    private static final SeedFile PLATFORM =
            new SeedFile("Navigation.json", SeedLevel.PLATFORM_GLOBAL, SeedFileMode.OVERWRITE, null, List.of());
    private static final SeedFile ONCE =
            new SeedFile("LeaveType.json", SeedLevel.TENANT, SeedFileMode.ONCE, null, List.of());
    private static final SeedFile APPEND =
            new SeedFile("MailTemplate.json", SeedLevel.TENANT, SeedFileMode.APPEND, null, List.of());
    private static final SeedFile OVERWRITE =
            new SeedFile("Role.json", SeedLevel.TENANT, SeedFileMode.OVERWRITE, null, List.of());

    private static final LocalDateTime OLDER_BUILD = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDateTime NEWER_BUILD = LocalDateTime.of(2026, 10, 20, 9, 0);

    private SeedLoadRecordService recordService;
    private SeedCatalog catalog;
    private SeedLoadLog loadLog;

    @BeforeEach
    void setUp() {
        recordService = mock(SeedLoadRecordService.class);
        catalog = mock(SeedCatalog.class);
        when(catalog.checksum(any(SeedFile.class))).thenReturn("v2");
        loadLog = new SeedLoadLog(mock(SeedLoadBatchService.class), recordService, catalog);
    }

    @Test
    void aFileNeverLoadedIsPending() {
        assertEquals(FileState.NOT_LOADED, loadLog.state(ONCE, null));
        assertTrue(FileState.NOT_LOADED.pending());
    }

    @Test
    void aFileLoadedAsItIsNowIsDone() {
        assertEquals(FileState.LOADED, stateOf(ONCE, record(1, "v2", SeedRecordStatus.SUCCEEDED)));
    }

    @Test
    void aChangedFileReloadsUnlessItRunsOnce() {
        SeedLoadRecord old = record(1, "v1", SeedRecordStatus.SUCCEEDED);
        assertEquals(FileState.CHANGED, stateOf(PLATFORM, old));
        assertEquals(FileState.CHANGED, stateOf(OVERWRITE, old));
        // An append file runs again too, for the rows a tenant lacks.
        assertEquals(FileState.CHANGED, stateOf(APPEND, old));
        // A load-once tenant file edited after it ran: the change reaches new tenants only.
        assertEquals(FileState.CHANGED_AFTER_LOAD, stateOf(ONCE, old));
        assertTrue(!FileState.CHANGED_AFTER_LOAD.pending());
    }

    @Test
    void aFailureIsPendingUntilARunSucceeds() {
        assertEquals(FileState.FAILED, stateOf(ONCE, record(1, "v2", SeedRecordStatus.FAILED)));
        // A changed overwrite file whose reload failed is still owed.
        assertEquals(FileState.FAILED, stateOf(OVERWRITE,
                record(1, "v1", SeedRecordStatus.SUCCEEDED), record(2, "v2", SeedRecordStatus.FAILED)));
        // And a success after the failure settles it.
        assertEquals(FileState.LOADED, stateOf(ONCE,
                record(1, "v2", SeedRecordStatus.FAILED), record(2, "v2", SeedRecordStatus.SUCCEEDED)));
    }

    @Test
    void aFileANewerBuildLoadedIsNotReloadedByAnOlderOne() {
        // A rollback: the database holds what the newer build loaded, and this build carries the older file.
        when(catalog.buildTime()).thenReturn(OLDER_BUILD);
        assertEquals(FileState.LOADED_BY_NEWER_BUILD, stateOf(PLATFORM, loadedBy(NEWER_BUILD)));
        assertEquals(FileState.LOADED_BY_NEWER_BUILD, stateOf(OVERWRITE, loadedBy(NEWER_BUILD)));
        assertEquals(FileState.LOADED_BY_NEWER_BUILD, stateOf(APPEND, loadedBy(NEWER_BUILD)));
        assertFalse(FileState.LOADED_BY_NEWER_BUILD.pending());
        // A run-once file does not reload anyway.
        assertEquals(FileState.CHANGED_AFTER_LOAD, stateOf(ONCE, loadedBy(NEWER_BUILD)));
    }

    @Test
    void aFileAnOlderBuildLoadedReloadsAsUsual() {
        when(catalog.buildTime()).thenReturn(NEWER_BUILD);
        assertEquals(FileState.CHANGED, stateOf(PLATFORM, loadedBy(OLDER_BUILD)));
    }

    @Test
    void withoutBuildTimesAChangeIsAChange() {
        // Nothing to compare: the record predates build times, or this build carries none.
        when(catalog.buildTime()).thenReturn(OLDER_BUILD);
        assertEquals(FileState.CHANGED, stateOf(PLATFORM, loadedBy(null)));
        when(catalog.buildTime()).thenReturn(null);
        assertEquals(FileState.CHANGED, stateOf(PLATFORM, loadedBy(NEWER_BUILD)));
    }

    private static SeedLoadRecord loadedBy(LocalDateTime buildTime) {
        SeedLoadRecord record = record(1, "v1", SeedRecordStatus.SUCCEEDED);
        record.setBuildTime(buildTime);
        return record;
    }

    private FileState stateOf(SeedFile file, SeedLoadRecord... records) {
        for (SeedLoadRecord record : records) {
            record.setFileName(file.file());
        }
        // Handed over newest first: the records are ordered by id, not by how the store returns them.
        when(recordService.searchList(any(Filters.class))).thenReturn(List.of(records).reversed());
        Map<String, SeedLoadLog.FileRecords> byFile = loadLog.platformRecords();
        return loadLog.state(file, byFile.get(file.file()));
    }

    private static SeedLoadRecord record(long id, String checksum, SeedRecordStatus status) {
        SeedLoadRecord record = new SeedLoadRecord();
        record.setId(id);
        record.setChecksum(checksum);
        record.setStatus(status);
        return record;
    }
}
