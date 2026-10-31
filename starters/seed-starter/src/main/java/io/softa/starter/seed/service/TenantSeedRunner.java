package io.softa.starter.seed.service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.seed.dto.*;
import io.softa.starter.seed.entity.SeedLoadRecord;
import io.softa.starter.seed.entity.SysPreData;
import io.softa.starter.seed.event.SeedFileLoadedEvent;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.enums.SeedLoadType;
import io.softa.starter.seed.enums.SeedRecordStatus;
import io.softa.starter.seed.manifest.SeedFile;

/**
 * Runs the tenant seed files in tenants.
 *
 * <p>A tenant runs each file it is due once, unless the manifest gives the file a mode that runs it again whenever
 * it changes: {@code append}, creating only the rows the tenant lacks, or {@code overwrite}, loading it whole.
 * Every file runs in its own transaction, and a tenant stops at its first failure — later files may refer to
 * the failed one's rows — with every other tenant carrying on. What succeeded is recorded with the data it
 * wrote, so a run that is interrupted, or a failure once fixed, is simply run again: only what has not
 * succeeded runs.
 *
 * <p>The platform admin can also reapply files: the named files run again in the named tenants whatever their
 * state, each loading whole over the tenant's copy, as an overwrite file does.
 *
 * <p>Runs over many tenants go to the background, one tenant after another, and so do reapplies; a new tenant's
 * setup, and the recording that initializes an environment, run in the caller.
 */
@Slf4j
@Service
public class TenantSeedRunner {

    private final SeedCatalog catalog;
    private final SeedLoadLog loadLog;
    private final TenantSeedPlanner planner;
    private final SysPreDataService preDataService;
    private final CacheService cacheService;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher eventPublisher;

    public TenantSeedRunner(SeedCatalog catalog, SeedLoadLog loadLog, TenantSeedPlanner planner,
                            SysPreDataService preDataService, CacheService cacheService,
                            PlatformTransactionManager transactionManager,
                            ApplicationEventPublisher eventPublisher) {
        this.catalog = catalog;
        this.loadLog = loadLog;
        this.planner = planner;
        this.preDataService = preDataService;
        this.cacheService = cacheService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.eventPublisher = eventPublisher;
    }

    // ─────────────────────────── running ───────────────────────────

    /**
     * Run the pending tenant files in the given tenants, or in every set-up tenant, in the background.
     *
     * <p>Refused until the environment is initialized, which its first platform load does
     * ({@link PlatformSeedLoader#load}). In an environment that held seed data before the records, a run would
     * otherwise load every tenant file again over what the tenants changed since; a new environment initializes
     * too, recording nothing, so there is one order of steps everywhere — and platform files load before tenant
     * files anyway.
     *
     * @param tenantIds tenants to run; null for every one that is active, suspended or closed. An empty list
     *                  is refused: whoever sends one picked nobody, which is the opposite of everybody
     * @return the batch to follow
     */
    public Long run(List<Long> tenantIds) {
        if (tenantIds != null && tenantIds.isEmpty()) {
            throw new BusinessException("Nothing was run: no tenant was picked.");
        }
        if (tenantIds != null && tenantIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException("Nothing was run: the list of tenants holds an empty entry.");
        }
        if (!loadLog.initialized()) {
            throw new BusinessException("Nothing was run: this environment has not been initialized. Load the "
                    + "platform files once from the Seed Data page first — that first load records the tenant files "
                    + "tenants already have as run, so a run does not load them again over what the tenants changed."
                    + " Then run.");
        }
        loadLog.requireIdle();
        boolean selected = tenantIds != null;
        List<Long> toRun = selected ? setUpTenants(tenantIds) : planner.provisionedTenantIds();
        Long batchId = loadLog.start(SeedLoadType.TENANT, selected ? toRun : null, toRun.size());
        inBackground("seed-tenant-run-" + batchId, () -> eachTenant(batchId, toRun, tenantId -> runTenant(batchId, tenantId)));
        return batchId;
    }

    /**
     * Run the named tenant files again in the given tenants, whatever their state, in the background — each file
     * loading whole over the tenant's copy, as an overwrite file does: the rows the tenant has are rewritten from
     * the file, the seeded rows it deleted come back, and the seeded children the file no longer lists are
     * deleted. What the tenant changed in those rows is lost; frozen rows are still left alone.
     *
     * <p>A tenant file runs once per tenant, and what the tenant changes afterwards is the tenant's. This is the
     * platform admin's way past that, for a file whose current content tenants that ran it should have — while a
     * seed is still being written, or for a tenant whose copy went wrong. The files run in manifest order, each in
     * its own transaction, and a tenant stops at its first failure, as a run does; each run is recorded, so a file
     * that succeeds reads as loaded afterwards. Unlike a run it does not wait for the environment's initialization,
     * which exists so a run does not load files over what tenants changed: a reapply does that on purpose.
     *
     * @param tenantIds set-up tenants to run the files in
     * @param fileNames tenant files, by name, every one of which each of the tenants is due
     * @return the batch to follow
     */
    public Long reapply(List<Long> tenantIds, List<String> fileNames) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            throw new BusinessException("Nothing was reapplied: no tenant was picked.");
        }
        if (tenantIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException("Nothing was reapplied: the list of tenants holds an empty entry.");
        }
        if (fileNames == null || fileNames.isEmpty()) {
            throw new BusinessException("Nothing was reapplied: no file was picked.");
        }
        if (fileNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new BusinessException("Nothing was reapplied: the list of files holds an empty entry.");
        }
        Set<String> picked = new LinkedHashSet<>(fileNames);
        List<String> unknown = picked.stream()
                .filter(name -> catalog.manifest().file(name).filter(file -> !file.level().isPlatform()).isEmpty())
                .toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException("Nothing was reapplied: {0} are not tenant files of the seed manifest.",
                    unknown.toString());
        }
        loadLog.requireIdle();
        List<Long> tenants = setUpTenants(tenantIds);
        Map<Long, List<SeedFile>> filesByTenant = new LinkedHashMap<>();
        Map<Long, List<String>> notDue = new LinkedHashMap<>();
        for (Long tenantId : tenants) {
            List<SeedFile> files = planner.dueFiles(tenantId).stream()
                    .filter(file -> picked.contains(file.file()))
                    .toList();
            filesByTenant.put(tenantId, files);
            List<String> missing = picked.stream()
                    .filter(name -> files.stream().noneMatch(file -> file.file().equals(name)))
                    .toList();
            if (!missing.isEmpty()) {
                notDue.put(tenantId, missing);
            }
        }
        if (!notDue.isEmpty()) {
            throw new BusinessException("Nothing was reapplied: a tenant runs only the tenant files its plan and its "
                    + "countries take, and these are not theirs, by tenant: {0}.", notDue.toString());
        }
        Long batchId = loadLog.start(SeedLoadType.REAPPLY, tenants, tenants.size());
        inBackground("seed-tenant-reapply-" + batchId, () -> eachTenant(batchId, tenants,
                tenantId -> runFiles(batchId, tenantId, filesByTenant.get(tenantId), file -> LoadMode.OVERWRITE)));
        return batchId;
    }

    /**
     * Set a new tenant up with the tenant files it is due — the same run, for one tenant, in the caller. A
     * failure is recorded and thrown, so the caller retries; the retry continues where this stopped.
     *
     * @param tenantId the tenant being set up
     */
    public void provision(Long tenantId) {
        Long batchId = loadLog.start(SeedLoadType.PROVISION, List.of(tenantId), 1);
        TenantOutcome outcome;
        try {
            outcome = runTenant(batchId, tenantId);
        } catch (RuntimeException e) {
            loadLog.finish(batchId, SeedLoadStatus.FAILED, 0, SeedErrors.messageOf(e));
            throw e;
        }
        loadLog.progress(batchId, 1, outcome.failed() ? 1 : 0, outcome.files());
        if (outcome.failed()) {
            loadLog.finish(batchId, SeedLoadStatus.FAILED, outcome.files(), outcome.error());
            throw new BusinessException("Seed data of tenant {0} stopped at {1}", String.valueOf(tenantId),
                    outcome.error());
        }
        loadLog.finish(batchId, SeedLoadStatus.SUCCEEDED, outcome.files(), null);
    }

    /**
     * Record as run, in every set-up tenant, the tenant files it already has rows of, changing no data — so the
     * first run does not load those files again over what the tenants changed since. Files a tenant has
     * nothing of are left for the next run, and so are the files that run again whenever they change: an append
     * file's run creates only the rows a tenant lacks, and an overwrite file runs again anyway. Part of
     * initializing an environment, which the first platform load does ({@link PlatformSeedLoader#load}) and
     * which answers with what it did, so it runs in the caller.
     *
     * @return how the {@code Initialize} batch ended
     */
    BatchOutcome recordExistingFiles() {
        List<Long> tenants = planner.provisionedTenantIds();
        Long batchId = loadLog.start(SeedLoadType.INITIALIZE, null, tenants.size());
        Map<String, Map<String, List<String>>> preIdsByFile = new HashMap<>();
        return eachTenant(batchId, tenants, tenantId -> recordExisting(batchId, tenantId, preIdsByFile));
    }

    /** How a batch over tenants ended: its status, the tenants it took, how many failed, and the files. */
    record BatchOutcome(Long batchId, SeedLoadStatus status, int tenants, int failedTenants, int files) {
    }

    /** What one tenant's run did: files run, and the failure it stopped at, if any. */
    record TenantOutcome(int files, String error) {
        boolean failed() {
            return error != null;
        }
    }

    /** Run what is pending in one tenant, stopping at the first failure. */
    TenantOutcome runTenant(Long batchId, Long tenantId) {
        Map<String, SeedLoadLog.FileRecords> records =
                loadLog.tenantRecords(List.of(tenantId)).getOrDefault(tenantId, Map.of());
        List<SeedFile> pending = planner.dueFiles(tenantId).stream()
                .filter(file -> loadLog.state(file, records.get(file.file())).pending())
                .toList();
        return runFiles(batchId, tenantId, pending, TenantSeedRunner::modeOf);
    }

    /**
     * The mode a run writes a file in: whole for an overwrite file, only the rows the tenant lacks for an append
     * file, and only what the file names for a file that runs once.
     */
    private static LoadMode modeOf(SeedFile file) {
        return switch (file.mode()) {
            case OVERWRITE -> LoadMode.OVERWRITE;
            case APPEND -> LoadMode.APPEND;
            default -> LoadMode.ADDITIVE;
        };
    }

    /** Run these files in one tenant, in order, each in its mode, stopping at the first failure. */
    private TenantOutcome runFiles(Long batchId, Long tenantId, List<SeedFile> files,
                                   Function<SeedFile, LoadMode> mode) {
        String tenantName = planner.tenantName(tenantId);
        int ran = 0;
        String error = null;
        for (SeedFile file : files) {
            error = runFile(batchId, tenantId, tenantName, file, mode.apply(file));
            if (error != null) {
                error = file.file() + ": " + error;
                break;
            }
            ran++;
        }
        if (ran > 0) {
            // New roles and grants change what this tenant's users may do; their cached snapshots go.
            clearCachedPermissions(tenantId);
        }
        return new TenantOutcome(ran, error);
    }

    /**
     * Run one file in one tenant, recording it with the data it wrote.
     *
     * @return null when it succeeded, else why it failed — recorded too
     */
    private String runFile(Long batchId, Long tenantId, String tenantName, SeedFile file, LoadMode mode) {
        LocalDateTime start = LocalDateTime.now();
        try {
            transaction.executeWithoutResult(status -> {
                LoadedRows loaded = preDataService.loadPreTenantData(List.of(file.file()), tenantId, mode);
                if (!loaded.isEmpty()) {
                    announceLoaded(tenantId, file, loaded);
                }
                loadLog.record(batchId, tenantId, tenantName, file, SeedRecordStatus.SUCCEEDED, null, start);
            });
            return null;
        } catch (RuntimeException e) {
            String error = SeedErrors.messageOf(e);
            log.warn("Seed file {} failed in tenant {}: {}", file.file(), tenantId, error, e);
            loadLog.record(batchId, tenantId, tenantName, file, SeedRecordStatus.FAILED, error, start);
            return error;
        }
    }

    /**
     * Publish {@link SeedFileLoadedEvent} in the tenant's context with permission checks skipped,
     * like the load itself: listeners act on the tenant's rows, whoever started the run.
     */
    private void announceLoaded(Long tenantId, SeedFile file, LoadedRows loaded) {
        ContextUtils.inTenantContext(tenantId, () -> eventPublisher.publishEvent(
                new SeedFileLoadedEvent(tenantId, file.file(), loaded.created(), loaded.updated())));
    }

    /** Record the tenant files the tenant already has rows of, as run; the outcome counts them. */
    TenantOutcome recordExisting(Long batchId, Long tenantId, Map<String, Map<String, List<String>>> preIdsByFile) {
        Map<String, SeedLoadLog.FileRecords> records =
                loadLog.tenantRecords(List.of(tenantId)).getOrDefault(tenantId, Map.of());
        Set<String> bound = new HashSet<>();
        ContextUtils.inSystemContext(() -> preDataService.searchList(new Filters().eq(SysPreData::getTenantId, tenantId)))
                .forEach(binding -> bound.add(binding.getModel() + "/" + binding.getPreId()));
        String tenantName = planner.tenantName(tenantId);
        int recorded = 0;
        for (SeedFile file : catalog.manifest().tenantFiles()) {
            SeedLoadLog.FileRecords fileRecords = records.get(file.file());
            if (file.reloadsWhenChanged() || (fileRecords != null && fileRecords.latestSuccess() != null)) {
                continue;
            }
            Map<String, List<String>> preIds = preIdsByFile.computeIfAbsent(file.file(),
                    name -> preDataService.filePreIds(file.level().dataDir(), name));
            boolean present = preIds.entrySet().stream()
                    .anyMatch(entry -> entry.getValue().stream().anyMatch(preId -> bound.contains(entry.getKey() + "/" + preId)));
            if (present) {
                loadLog.record(batchId, tenantId, tenantName, file, SeedRecordStatus.SUCCEEDED, null, LocalDateTime.now());
                recorded++;
            }
        }
        return new TenantOutcome(recorded, null);
    }

    /**
     * Take the tenants one after another, counting progress on the batch, and close it: Succeeded when every
     * tenant made it, Failed when none did, PartiallyFailed otherwise.
     */
    private BatchOutcome eachTenant(Long batchId, List<Long> tenants, Function<Long, TenantOutcome> perTenant) {
        int finished = 0;
        int failed = 0;
        int files = 0;
        try {
            for (Long tenantId : tenants) {
                TenantOutcome outcome = perTenant.apply(tenantId);
                finished++;
                failed += outcome.failed() ? 1 : 0;
                files += outcome.files();
                loadLog.progress(batchId, finished, failed, files);
            }
            SeedLoadStatus status = failed == 0 ? SeedLoadStatus.SUCCEEDED
                    : failed == finished ? SeedLoadStatus.FAILED : SeedLoadStatus.PARTIALLY_FAILED;
            loadLog.finish(batchId, status, files, failed == 0 ? null : failed + " tenant(s) failed; see their records.");
            return new BatchOutcome(batchId, status, tenants.size(), failed, files);
        } catch (RuntimeException | Error e) {
            log.error("Seed data batch {} stopped", batchId, e);
            loadLog.finish(batchId, SeedLoadStatus.FAILED, files, SeedErrors.messageOf(e));
            return new BatchOutcome(batchId, SeedLoadStatus.FAILED, tenants.size(), failed, files);
        }
    }

    /** The tenants picked, once each, in the order picked; refused unless every one of them is set up. */
    private List<Long> setUpTenants(List<Long> tenantIds) {
        List<Long> provisioned = planner.provisionedTenantIds();
        List<Long> tenants = List.copyOf(new LinkedHashSet<>(tenantIds));
        List<Long> notSetUp = tenants.stream().filter(id -> !provisioned.contains(id)).toList();
        if (!notSetUp.isEmpty()) {
            throw new BusinessException("Tenants {0} have not finished their setup; seed data runs in "
                    + "active, suspended and closed tenants.", notSetUp.toString());
        }
        return tenants;
    }

    /** Run in a thread of its own, as the caller: what it writes is attributed to whoever started it. */
    private static void inBackground(String name, Runnable work) {
        Context caller = ContextHolder.cloneContext();
        Thread.ofVirtual().name(name).start(() -> ContextHolder.runWith(caller, work));
    }

    private void clearCachedPermissions(Long tenantId) {
        try {
            cacheService.clearByPrefix(PlatformSeedLoader.PERMISSION_CACHE_PREFIX + tenantId + ":");
        } catch (RuntimeException e) {
            log.error("Could not clear the cached permissions of tenant {}", tenantId, e);
        }
    }

    // ─────────────────────────── reading ───────────────────────────

    /** What a run of every set-up tenant would do. */
    public TenantPendingSummary pending() {
        List<Long> tenants = planner.provisionedTenantIds();
        Map<Long, Map<String, SeedLoadLog.FileRecords>> records = loadLog.tenantRecords(tenants);
        int tenantCount = 0;
        int fileCount = 0;
        for (Long tenantId : tenants) {
            Map<String, SeedLoadLog.FileRecords> tenantRecords = records.getOrDefault(tenantId, Map.of());
            long pending = planner.dueFiles(tenantId).stream()
                    .filter(file -> loadLog.state(file, tenantRecords.get(file.file())).pending())
                    .count();
            if (pending > 0) {
                tenantCount++;
                fileCount += (int) pending;
            }
        }
        return new TenantPendingSummary(tenantCount, fileCount);
    }

    /** These tenants' seed files in numbers. */
    public List<TenantSeedSummary> summaries(List<Long> tenantIds) {
        Map<Long, Map<String, SeedLoadLog.FileRecords>> records = loadLog.tenantRecords(tenantIds);
        List<TenantSeedSummary> summaries = new ArrayList<>();
        for (Long tenantId : new LinkedHashSet<>(tenantIds)) {
            Map<String, SeedLoadLog.FileRecords> tenantRecords = records.getOrDefault(tenantId, Map.of());
            int loaded = 0;
            int pending = 0;
            int failed = 0;
            int changedAfterLoad = 0;
            List<SeedFile> due = planner.dueFiles(tenantId);
            for (SeedFile file : due) {
                FileState state = loadLog.state(file, tenantRecords.get(file.file()));
                loaded += state == FileState.LOADED || state == FileState.CHANGED_AFTER_LOAD
                        || state == FileState.LOADED_BY_NEWER_BUILD ? 1 : 0;
                pending += state.pending() ? 1 : 0;
                failed += state == FileState.FAILED ? 1 : 0;
                changedAfterLoad += state == FileState.CHANGED_AFTER_LOAD ? 1 : 0;
            }
            LocalDateTime lastLoadedAt = tenantRecords.values().stream()
                    .map(SeedLoadLog.FileRecords::latestSuccess)
                    .filter(Objects::nonNull)
                    .map(SeedLoadRecord::getEndTime)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            summaries.add(new TenantSeedSummary(tenantId, due.size(), loaded, pending, failed, changedAfterLoad,
                    lastLoadedAt));
        }
        return summaries;
    }

    /** Every tenant file the tenant is due, and where each stands. */
    public TenantSeedDetail detail(Long tenantId) {
        Map<String, SeedLoadLog.FileRecords> records =
                loadLog.tenantRecords(List.of(tenantId)).getOrDefault(tenantId, Map.of());
        List<TenantFileStatus> files = planner.dueFiles(tenantId).stream().map(file -> {
            SeedLoadLog.FileRecords fileRecords = records.get(file.file());
            SeedLoadRecord success = fileRecords == null ? null : fileRecords.latestSuccess();
            SeedLoadRecord latest = fileRecords == null ? null : fileRecords.latest();
            return new TenantFileStatus(file.file(), file.module(), file.countries(), file.mode(),
                    loadLog.state(file, fileRecords), catalog.checksum(file),
                    success == null ? null : success.getChecksum(),
                    success == null ? null : success.getEndTime(),
                    latest != null && latest.getStatus() == SeedRecordStatus.FAILED ? latest.getErrorMessage() : null);
        }).toList();
        return new TenantSeedDetail(tenantId, files);
    }
}
