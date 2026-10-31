package io.softa.starter.seed.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.seed.dto.FileState;
import io.softa.starter.seed.dto.PlatformFileStatus;
import io.softa.starter.seed.dto.PlatformLoadResult;
import io.softa.starter.seed.dto.PlatformSeedStatus;
import io.softa.starter.seed.dto.SeedInitialization;
import io.softa.starter.seed.entity.SysPreData;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.enums.SeedLoadType;
import io.softa.starter.seed.enums.SeedRecordStatus;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedLevel;

/**
 * Loads the platform seed files — the shared rows every tenant reads, and the platform tenant's own.
 *
 * <p>A platform file is the data, so it is loaded whole whenever it was never loaded or has changed since. The
 * pending files load in one transaction, in manifest order: either all of them or none. Afterwards the
 * permission snapshots and entitlements cached in Redis are cleared.
 *
 * <p>The files the build owns are in mode {@code startup} in the manifest, and load at startup wherever they are
 * pending ({@link #loadStartupFiles}), before the application takes traffic and before the in-memory snapshots read
 * by every request (endpoint index, sensitive field sets, navigation) are built from their rows. The Seed Data page
 * loads the other files, and never those: a page load at any other time would reach no snapshot until the next
 * start.
 *
 * <p>An environment's first load from the page initializes it, before loading anything: in every set-up tenant it
 * records as run the tenant files the tenant already has rows of, changing no data, so the first tenant run does
 * not load them again over what the tenants changed since. Tenant runs are refused until then; setting up a new
 * tenant is not.
 */
@Slf4j
@Service
public class PlatformSeedLoader {

    /** Where the permission engine caches each user's snapshot: {@code perm:{tenant}:user:{user}}. */
    static final String PERMISSION_CACHE_PREFIX = "perm:";

    private final SeedCatalog catalog;
    private final SeedLoadLog loadLog;
    private final SysPreDataService preDataService;
    private final CacheService cacheService;
    private final TenantSeedRunner tenantRunner;
    private final TransactionTemplate transaction;

    public PlatformSeedLoader(SeedCatalog catalog, SeedLoadLog loadLog, SysPreDataService preDataService,
                              CacheService cacheService, TenantSeedRunner tenantRunner,
                              PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.loadLog = loadLog;
        this.preDataService = preDataService;
        this.cacheService = cacheService;
        this.tenantRunner = tenantRunner;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The platform files of the running build against what the platform last loaded. */
    public PlatformSeedStatus status() {
        Map<String, SeedLoadLog.FileRecords> records = loadLog.platformRecords();
        List<PlatformFileStatus> files = catalog.manifest().platformFiles().stream().map(file -> {
            SeedLoadLog.FileRecords fileRecords = records.get(file.file());
            var success = fileRecords == null ? null : fileRecords.latestSuccess();
            var latest = fileRecords == null ? null : fileRecords.latest();
            return new PlatformFileStatus(file.file(), file.level(), file.mode(), loadLog.state(file, fileRecords),
                    catalog.checksum(file),
                    success == null ? null : success.getChecksum(),
                    success == null ? null : success.getEndTime(),
                    success == null ? null : success.getCreatedBy(),
                    latest != null && latest.getStatus() == SeedRecordStatus.FAILED ? latest.getErrorMessage() : null);
        }).toList();
        return new PlatformSeedStatus(catalog.appVersion(), catalog.buildTime(), catalog.startedAt(),
                loadLog.lastPlatformLoad(), loadLog.initialized(), loadLog.running().orElse(null), files);
    }

    /**
     * Load the pending platform files — all of them, or the ones named — leaving out the files the build owns,
     * which load at startup. The environment's first load initializes it first (see the class comment).
     *
     * <p>That first load decides on the recording: when it fails outright nothing is loaded, and the next load
     * tries again. When the files fail to load after it, the environment is initialized all the same.
     *
     * @param fileNames pending files to load, in manifest order whatever order they are named in, the others
     *                  staying pending; null for every pending one — refused on the first load of an environment
     *                  that already holds platform seed data, where loading every file would put each one over
     *                  what was changed on the pages since. An empty list is refused: whoever sends one picked
     *                  nothing, which is the opposite of everything
     * @return how it went; on a failure nothing is loaded
     */
    public PlatformLoadResult load(List<String> fileNames) {
        if (loadLog.initialized()) {
            loadLog.requireIdle();
            return loadFiles(selectPending(fileNames));
        }
        if (fileNames == null && holdsPlatformSeedData()) {
            throw new BusinessException("Nothing was loaded: this environment already holds platform seed data, and "
                    + "loading every platform file would put each one over what was changed on the pages since. "
                    + "Pick the files to load, leaving out the ones whose rows were changed on the pages.");
        }
        // The names are checked before anything is written, so a typo costs nothing.
        List<SeedFile> files = selectPending(fileNames);
        loadLog.requireIdle();
        TenantSeedRunner.BatchOutcome recorded = tenantRunner.recordExistingFiles();
        SeedInitialization initialization = new SeedInitialization(recorded.batchId(), recorded.status(),
                recorded.tenants(), recorded.failedTenants(), recorded.files());
        if (recorded.status() == SeedLoadStatus.FAILED) {
            log.error("Initialize batch {} failed; no platform file was loaded", recorded.batchId());
            return new PlatformLoadResult(null, false, List.of(), null, "Nothing was loaded: this environment's "
                    + "first load records the tenant files tenants already have before it loads, and that failed. "
                    + "The error is on Initialize batch " + recorded.batchId() + ". Fix the cause and load again; "
                    + "a tenant recorded already is not recorded twice.", initialization);
        }
        return loadFiles(files).initializing(initialization);
    }

    /**
     * The files the build owns that are pending here: never loaded, changed since, or failed last time. A file a
     * newer build loaded is not: after a rollback, this build's older content does not go over the newer one.
     */
    public List<SeedFile> pendingStartupFiles() {
        List<SeedFile> startupFiles = catalog.manifest().startupFiles();
        if (startupFiles.isEmpty()) {
            return List.of();
        }
        Map<String, SeedLoadLog.FileRecords> records = loadLog.platformRecords();
        List<SeedFile> pending = new ArrayList<>();
        for (SeedFile file : startupFiles) {
            FileState state = loadLog.state(file, records.get(file.file()));
            if (state.pending()) {
                pending.add(file);
            } else if (state == FileState.LOADED_BY_NEWER_BUILD) {
                log.warn("{} was loaded by a newer build than the one running and is left as that build loaded it; "
                        + "this build's older content does not go over it.", file.file());
            }
        }
        return pending;
    }

    /**
     * Load the files the build owns that are pending here — the platform files in mode {@code startup}.
     * {@link SeedStartupLoad} calls it at startup, before the application takes traffic and before the in-memory
     * snapshots are built from the files' rows.
     *
     * <p>This does not initialize the environment — the first load from the page still does, after whatever the
     * deployment runs once the new build is up. Nor does it wait for another seed operation to finish: nothing else
     * loads these files, and a batch left Running by an instance that stopped part-way must not hold the start up.
     *
     * @return how the load went, or empty when no file the build owns is pending
     */
    public Optional<PlatformLoadResult> loadStartupFiles() {
        List<SeedFile> files = pendingStartupFiles();
        return files.isEmpty() ? Optional.empty() : Optional.of(loadFiles(files));
    }

    /** Whether platform seed rows were loaded here before: bindings at system scope. */
    private boolean holdsPlatformSeedData() {
        return ContextUtils.inSystemContext(() -> preDataService.exist(new Filters().isNotSet(SysPreData::getTenantId)));
    }

    /**
     * Load these platform files, in manifest order and one transaction, and record them. The caller has made
     * sure nothing else runs.
     *
     * @return how it went; on a failure nothing is loaded
     */
    private PlatformLoadResult loadFiles(List<SeedFile> files) {
        Long batchId = loadLog.start(SeedLoadType.PLATFORM, null, null);
        SeedFile[] current = new SeedFile[1];
        LocalDateTime[] started = new LocalDateTime[1];
        try {
            transaction.executeWithoutResult(status -> {
                for (SeedFile file : files) {
                    current[0] = file;
                    started[0] = LocalDateTime.now();
                    if (file.level() == SeedLevel.PLATFORM_TENANT) {
                        preDataService.loadPrePlatformData(List.of(file.file()), LoadMode.OVERWRITE);
                    } else {
                        preDataService.loadPreSystemData(List.of(file.file()), LoadMode.OVERWRITE);
                    }
                    loadLog.record(batchId, null, null, file, SeedRecordStatus.SUCCEEDED, null, started[0]);
                }
            });
        } catch (RuntimeException e) {
            String error = SeedErrors.messageOf(e);
            if (current[0] == null) {
                // Before the first file — the transaction could not even start: no file has a record to write,
                // and the batch carries the cause alone, rather than staying Running until it reads as stale.
                log.error("Platform seed load {} failed before loading any file", batchId, e);
                loadLog.finish(batchId, SeedLoadStatus.FAILED, 0, error);
                return new PlatformLoadResult(batchId, false, List.of(), null, error);
            }
            log.error("Platform seed load {} failed on {}", batchId, current[0].file(), e);
            loadLog.record(batchId, null, null, current[0], SeedRecordStatus.FAILED, error, started[0]);
            loadLog.finish(batchId, SeedLoadStatus.FAILED, 0, current[0].file() + ": " + error);
            return new PlatformLoadResult(batchId, false, List.of(), current[0].file(), error);
        }
        loadLog.finish(batchId, SeedLoadStatus.SUCCEEDED, files.size(), null);
        clearCachedPermissions();
        log.info("Platform seed load {}: {} file(s) loaded", batchId, files.size());
        return new PlatformLoadResult(batchId, true, files.stream().map(SeedFile::file).toList(), null, null);
    }

    /**
     * The pending platform files the page loads, or the named ones among them — all of which must be pending, and
     * none of which the build owns.
     */
    private List<SeedFile> selectPending(List<String> fileNames) {
        if (fileNames != null && fileNames.isEmpty()) {
            throw new BusinessException("Nothing was loaded: no platform file was picked.");
        }
        if (fileNames != null && fileNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new BusinessException("Nothing was loaded: the list of platform files holds an empty entry.");
        }
        Map<String, SeedLoadLog.FileRecords> records = loadLog.platformRecords();
        Map<SeedFile, FileState> states = new LinkedHashMap<>();
        catalog.manifest().platformFiles().stream()
                .filter(file -> !file.loadsAtStartup())
                .forEach(file -> states.put(file, loadLog.state(file, records.get(file.file()))));
        List<SeedFile> pending = states.entrySet().stream()
                .filter(entry -> entry.getValue().pending())
                .map(Map.Entry::getKey)
                .toList();
        if (fileNames == null) {
            if (pending.isEmpty()) {
                throw new BusinessException("Every platform seed file is loaded already.");
            }
            return pending;
        }
        Set<String> named = new LinkedHashSet<>(fileNames);
        List<String> owned = catalog.manifest().startupFiles().stream()
                .map(SeedFile::file)
                .filter(named::contains)
                .toList();
        if (!owned.isEmpty()) {
            throw new BusinessException("Nothing was loaded: {0} belongs to the build and loads at startup, before "
                    + "anything is read from it; loaded now, no instance would see it until it restarts.",
                    String.join(", ", owned));
        }
        List<String> loadedByNewerBuild = states.entrySet().stream()
                .filter(entry -> entry.getValue() == FileState.LOADED_BY_NEWER_BUILD
                        && named.contains(entry.getKey().file()))
                .map(entry -> entry.getKey().file())
                .toList();
        if (!loadedByNewerBuild.isEmpty()) {
            throw new BusinessException("Nothing was loaded: {0} was loaded by a newer build than the one running. "
                    + "Loading it here would put older content over newer — deploy that build again.",
                    String.join(", ", loadedByNewerBuild));
        }
        List<String> notPending = new ArrayList<>(named);
        notPending.removeAll(pending.stream().map(SeedFile::file).toList());
        if (!notPending.isEmpty()) {
            throw new BusinessException("Nothing was loaded: {0} is not pending. Refresh the page and try again.",
                    String.join(", ", notPending));
        }
        return pending.stream().filter(file -> named.contains(file.file())).toList();
    }

    /**
     * Platform rows feed every tenant's permission snapshot and entitlement, cached for up to a day. A failure
     * is only logged: the data is in, and the caches expire on their own.
     */
    private void clearCachedPermissions() {
        for (String prefix : List.of(PERMISSION_CACHE_PREFIX, RedisConstant.ENTITLEMENT)) {
            try {
                cacheService.clearByPrefix(prefix);
            } catch (RuntimeException e) {
                log.error("Platform seed load could not clear the {}* cache", prefix, e);
            }
        }
    }
}
