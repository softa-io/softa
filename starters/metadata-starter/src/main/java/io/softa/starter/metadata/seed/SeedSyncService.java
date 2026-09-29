package io.softa.starter.metadata.seed;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.broadcast.ClusterBroadcaster;
import io.softa.framework.orm.broadcast.ClusterEvents;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.seed.PlatformSeedState;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.metadata.entity.SeedFileVersion;
import io.softa.starter.metadata.entity.SeedSyncBatch;
import io.softa.starter.metadata.entity.SeedSyncTask;
import io.softa.starter.metadata.enums.SeedSyncStatus;
import io.softa.starter.metadata.enums.SeedSyncTaskStatus;
import io.softa.starter.metadata.enums.SeedSyncTriggerType;
import io.softa.starter.metadata.seed.SeedFileState.State;
import io.softa.starter.metadata.service.SeedFileVersionService;
import io.softa.starter.metadata.service.SeedSyncBatchService;
import io.softa.starter.metadata.service.SeedSyncTaskService;
import io.softa.starter.metadata.service.SysPreDataService;

import static io.softa.starter.metadata.seed.SyncSupport.asSystem;
import static io.softa.starter.metadata.seed.SyncSupport.messageOf;
import static io.softa.starter.metadata.seed.SyncSupport.truncate;

/**
 * Applies the seed files of the running release when the platform admin asks — never at deploy or startup.
 *
 * <p>A sync looks only at files whose content differs from the version the database last recorded. Pending
 * platform files are loaded first, once, in manifest order: the shared rows every tenant reads and the
 * platform tenant's own. Once anything is loaded, the cached permission snapshots and entitlements of every
 * tenant are cleared and every instance rebuilds its in-memory permission indexes. Then, if tenant files
 * changed, every synced tenant is brought up to date with them — only what it does not have yet is added —
 * one task per tenant, through the MQ ({@link TenantSeedSyncRunner}). The caller gets the batch at once and
 * follows its progress. One batch runs at a time.
 *
 * <p>Rows removed from a platform file are not deleted from the shared tables.
 */
@Slf4j
@Service
public class SeedSyncService implements PlatformSeedState {

    private static final String LOCK_KEY = "seed-sync:lock";
    /** Renewed after every file, so it only has to outlast the slowest single file. */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    /**
     * Caches derived from platform rows, cleared after a sync: the per-user permission snapshots (the
     * prefix the permission engine writes them under) and the per-tenant entitlements.
     */
    private static final List<String> DERIVED_CACHE_PREFIXES = List.of("perm:", RedisConstant.ENTITLEMENT);
    private static final Set<SeedLevel> PLATFORM_LEVELS = Set.of(SeedLevel.PLATFORM_GLOBAL, SeedLevel.PLATFORM_TENANT);

    private final ObjectProvider<SeedManifest> manifestProvider;
    private final SysPreDataService preDataService;
    private final SeedFileVersionService versionService;
    private final SeedSyncBatchService batchService;
    private final SeedSyncTaskService taskService;
    private final TenantSeedSyncRunner runner;
    private final TenantSeedSyncPublisher publisher;
    private final CacheService cacheService;
    private final StringRedisTemplate redisTemplate;
    private final ClusterBroadcaster broadcaster;
    private final String appVersion;
    private final LocalDateTime buildTime;

    /** File name → SHA-256. The classpath does not change while the application runs. */
    private volatile Map<String, String> checksums;

    public SeedSyncService(ObjectProvider<SeedManifest> manifestProvider,
                           SysPreDataService preDataService,
                           SeedFileVersionService versionService,
                           SeedSyncBatchService batchService,
                           SeedSyncTaskService taskService,
                           TenantSeedSyncRunner runner,
                           TenantSeedSyncPublisher publisher,
                           CacheService cacheService,
                           StringRedisTemplate redisTemplate,
                           ClusterBroadcaster broadcaster,
                           ObjectProvider<BuildProperties> buildProperties) {
        this.manifestProvider = manifestProvider;
        this.preDataService = preDataService;
        this.versionService = versionService;
        this.batchService = batchService;
        this.taskService = taskService;
        this.runner = runner;
        this.publisher = publisher;
        this.cacheService = cacheService;
        this.redisTemplate = redisTemplate;
        this.broadcaster = broadcaster;
        BuildProperties build = buildProperties.getIfAvailable();
        this.appVersion = build == null ? null : build.getVersion();
        this.buildTime = build == null || build.getTime() == null
                ? null : LocalDateTime.ofInstant(build.getTime(), ZoneId.systemDefault());
    }

    // ─────────────────────────── status ───────────────────────────

    public SeedSyncStatusView status() {
        SeedManifest manifest = manifestProvider.getIfAvailable();
        if (manifest == null) {
            return new SeedSyncStatusView(List.of(), 0, 0, false, 0, 0, 0, null, null, null, null, null, false,
                    "The application ships no seed manifest.", null, appVersion, buildTime);
        }
        return asSystem(() -> {
            List<SeedFileState> files = classify(manifest);
            SeedSyncBatch running = runningBatch().orElse(null);
            String blockReason = syncBlocker(files, running);
            Integer finishedTenants = running == null || running.getTotalTenants() == null ? null
                    : nullToZero(running.getSucceededTenants()) + nullToZero(running.getFailedTenants())
                    + nullToZero(running.getSkippedTenants());
            return new SeedSyncStatusView(files, count(files, State.PENDING), count(files, State.ROLLED_BACK),
                    tenantBaseline(manifest),
                    (int) pendingOf(files).stream().filter(f -> PLATFORM_LEVELS.contains(f.level())).count(),
                    (int) pendingOf(files).stream().filter(f -> f.level() == SeedLevel.TENANT).count(),
                    runner.syncedTenantIds().size(),
                    running == null ? null : running.getId(),
                    running == null ? null : running.getLoadedFiles(),
                    running == null ? null : running.getTotalFiles(),
                    finishedTenants,
                    running == null ? null : running.getTotalTenants(),
                    blockReason == null, blockReason, creationBlocker(files), appVersion, buildTime);
        });
    }

    @Override
    public Optional<String> tenantCreationBlocker() {
        SeedManifest manifest = manifestProvider.getIfAvailable();
        if (manifest == null) {
            return Optional.empty();
        }
        return asSystem(() -> Optional.ofNullable(creationBlocker(classify(manifest))));
    }

    /**
     * Only the platform files hold tenant creation back: a new tenant loads the current tenant files
     * itself. And only while they are being loaded — the tenants being brought up to date afterwards do
     * not concern a new one.
     */
    private String creationBlocker(List<SeedFileState> files) {
        if (platformStepRunning()) {
            return "Platform seed data is being synced. Create the tenant once the sync finishes.";
        }
        List<SeedFileState> platform = files.stream().filter(f -> PLATFORM_LEVELS.contains(f.level())).toList();
        List<String> rolledBack = names(platform, State.ROLLED_BACK);
        if (!rolledBack.isEmpty()) {
            return rollbackMessage(rolledBack);
        }
        int pending = count(platform, State.PENDING);
        if (pending > 0) {
            return pending + " platform seed file(s) of this release are not synced yet. "
                    + "Sync them on the Sync Seed Data page before creating a tenant.";
        }
        return null;
    }

    // ─────────────────────────── sync ───────────────────────────

    /**
     * Start a sync of every pending file.
     *
     * <p>Tenant files reach tenants as what this release changed in them: the rows it added (created where
     * a tenant does not have them) and the declared pushes. The first sync of all records the current tenant
     * files as the baseline and reaches no tenant — a gap a tenant had before that is not filled.
     *
     * @param tenantIds limit the tenants to these, to try a change on a few before everyone; the tenant files
     *                  then stay pending for the sync that reaches every tenant. Null or empty for every tenant
     * @return the batch now running in the background, or empty when there was nothing to sync
     */
    public Optional<SeedSyncBatch> sync(List<Long> tenantIds) {
        SeedManifest manifest = requireManifest();
        List<Long> selected = tenantIds == null || tenantIds.isEmpty() ? null
                : List.copyOf(new LinkedHashSet<>(tenantIds));
        return withLock(lockKey -> {
            List<SeedFileState> files = classify(manifest);
            List<String> rolledBack = names(files, State.ROLLED_BACK);
            if (!rolledBack.isEmpty()) {
                throw new BusinessException(rollbackMessage(rolledBack));
            }
            List<SeedFileState> pending = pendingOf(files);
            if (pending.isEmpty()) {
                return null;
            }
            List<SeedFileState> platform = pending.stream().filter(f -> PLATFORM_LEVELS.contains(f.level())).toList();
            List<SeedFileState> tenant = pending.stream().filter(f -> f.level() == SeedLevel.TENANT).toList();
            if (selected != null) {
                List<Long> unknown = new ArrayList<>(selected);
                unknown.removeAll(runner.syncedTenantIds());
                if (!unknown.isEmpty()) {
                    throw new BusinessException("Tenants " + unknown + " are not active or suspended; a sync does "
                            + "not reach them.");
                }
            }
            boolean baseline = tenantBaseline(manifest);
            List<TenantFileChange> changes = tenant.stream()
                    .map(file -> baseline ? new TenantFileChange(file.file(), List.of(), List.of()) : changeOf(file))
                    .toList();
            SeedSyncBatch batch = createBatch(SeedSyncTriggerType.MANUAL, null, platform, changes, selected);
            return new Plan(batch, () -> {
                if (loadPlatformFiles(batch, platform, lockKey)) {
                    startTenantStep(batch, tenant, changes, selected, baseline);
                }
            });
        });
    }

    /**
     * What this release changed in a tenant file: its rows against those of the version last recorded. A
     * file with no version yet — new since the baseline — is all added. A version recorded before rows
     * were, counts as the baseline.
     */
    private TenantFileChange changeOf(SeedFileState file) {
        Set<String> current = preDataService.rowKeysOf(file.level().getDataDir(), file.file());
        SeedFileVersion previous = latestVersion(file.file());
        if (previous == null) {
            return new TenantFileChange(file.file(), List.copyOf(current), List.of());
        }
        if (StringUtils.isBlank(previous.getRowKeys())) {
            return new TenantFileChange(file.file(), List.of(), List.of());
        }
        Set<String> before = new LinkedHashSet<>(JsonUtils.stringToObject(previous.getRowKeys(),
                new TypeReference<List<String>>() {}));
        List<String> added = current.stream().filter(key -> !before.contains(key)).toList();
        List<String> removed = before.stream().filter(key -> !current.contains(key)).toList();
        return new TenantFileChange(file.file(), added, removed);
    }

    /** No tenant file has a version yet: the next sync records the baseline and reaches no tenant. */
    private boolean tenantBaseline(SeedManifest manifest) {
        List<String> tenantFiles = manifest.loadOrder(SeedLevel.TENANT);
        return tenantFiles.isEmpty()
                || !versionService.exist(new Filters().in(SeedFileVersion::getFileName, tenantFiles));
    }

    private SeedFileVersion latestVersion(String fileName) {
        return versionService.searchList(new Filters().eq(SeedFileVersion::getFileName, fileName)).stream()
                .filter(v -> v.getSyncedTime() != null)
                .max(Comparator.comparing(SeedFileVersion::getSyncedTime))
                .orElse(null);
    }

    /**
     * Re-run the tenants a finished batch failed on, with the tenant files that batch applied, as a new
     * ManualRetry batch.
     *
     * @return the new batch, or empty when the batch has no failed tenant
     */
    public Optional<SeedSyncBatch> retry(Long batchId) {
        requireManifest();
        return withLock(lockKey -> {
            SeedSyncBatch original = batchService.getById(batchId)
                    .orElseThrow(() -> new BusinessException("Seed sync batch " + batchId + " does not exist."));
            List<Long> failedTenants = taskService.searchList(new Filters()
                            .eq(SeedSyncTask::getBatchId, batchId)
                            .eq(SeedSyncTask::getStatus, SeedSyncTaskStatus.FAILED))
                    .stream().map(SeedSyncTask::getTenantId).toList();
            if (failedTenants.isEmpty()) {
                return null;
            }
            SeedSyncBatch batch = new SeedSyncBatch();
            batch.setStatus(SeedSyncStatus.RUNNING);
            batch.setTriggerType(SeedSyncTriggerType.MANUAL_RETRY);
            batch.setRetryOfBatchId(batchId);
            batch.setTotalFiles(0);
            batch.setLoadedFiles(0);
            batch.setTenantFileNames(original.getTenantFileNames());
            batch.setTenantChanges(original.getTenantChanges());
            batch.setAppVersion(appVersion);
            batch.setBuildTime(buildTime);
            batch.setStartTime(LocalDateTime.now());
            batch.setId(batchService.createOne(batch));
            return new Plan(batch, () -> dispatchTenants(batch, failedTenants));
        });
    }

    /** What a locked start hands to the background: the batch it created and the work left to do. */
    private record Plan(SeedSyncBatch batch, Runnable work) {}

    /**
     * Take the sync lock, prepare a batch under it, and run the rest in the background — releasing the
     * lock once the platform files are loaded and the tenants handed out. Refused while another batch is
     * still in progress: two batches writing the same tenant would collide.
     */
    private Optional<SeedSyncBatch> withLock(Function<String, Plan> prepare) {
        String lockKey = cacheService.getKeyPath(LOCK_KEY);
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL))) {
            throw new BusinessException("A seed sync is already running. Wait for it to finish.");
        }
        Plan plan;
        try {
            plan = asSystem(() -> {
                closeStaleBatches();
                return prepare.apply(lockKey);
            });
        } catch (RuntimeException e) {
            releaseLock(lockKey, token);
            throw e;
        }
        if (plan == null) {
            releaseLock(lockKey, token);
            return Optional.empty();
        }
        // The loaders stamp the caller as the author of what they write, so the caller's context goes along.
        Context caller = ContextHolder.cloneContext();
        Thread.ofVirtual().name("seed-sync-" + plan.batch().getId()).start(() -> ContextHolder.runWith(caller, () -> {
            try {
                plan.work().run();
            } catch (RuntimeException e) {
                log.error("Seed sync batch {} stopped", plan.batch().getId(), e);
            } finally {
                releaseLock(lockKey, token);
            }
        }));
        return Optional.of(plan.batch());
    }

    /**
     * The platform step: load the pending platform files, then make them take effect.
     *
     * @return whether it succeeded — the tenants are not touched when it did not
     */
    private boolean loadPlatformFiles(SeedSyncBatch batch, List<SeedFileState> files, String lockKey) {
        int loaded = 0;
        String error = null;
        for (SeedFileState file : files) {
            try {
                if (file.level() == SeedLevel.PLATFORM_TENANT) {
                    preDataService.loadPrePlatformData(List.of(file.file()));
                } else {
                    preDataService.loadPreSystemData(List.of(file.file()));
                }
                asSystem(() -> recordVersion(file, batch.getId()));
            } catch (RuntimeException e) {
                log.error("Seed sync batch {} failed on {}", batch.getId(), file.file(), e);
                error = file.file() + ": " + messageOf(e);
                break;
            }
            loaded++;
            int progress = loaded;
            asSystem(() -> updateBatch(batch.getId(), b -> b.setLoadedFiles(progress)));
            redisTemplate.expire(lockKey, LOCK_TTL);
        }
        if (loaded > 0) {
            error = join(error, refreshDerivedState());
        }
        if (error != null) {
            String failure = error;
            asSystem(() -> updateBatch(batch.getId(), b -> {
                b.setStatus(SeedSyncStatus.FAILED);
                b.setEndTime(LocalDateTime.now());
                b.setErrorMessage(truncate(failure));
            }));
            log.info("Seed sync batch {} stopped at the platform step: {} of {} file(s) loaded, error: {}",
                    batch.getId(), loaded, files.size(), failure);
            return false;
        }
        log.info("Seed sync batch {}: {} platform file(s) loaded", batch.getId(), loaded);
        return true;
    }

    /**
     * The tenant step. A sync that reaches every tenant records the tenant files' versions — the change is
     * now this batch's to deliver, to a failed tenant through a retry — and a limited one leaves them
     * pending. Then every tenant the change reaches gets a task; the baseline reaches none.
     */
    private void startTenantStep(SeedSyncBatch batch, List<SeedFileState> files, List<TenantFileChange> changes,
                                 List<Long> selected, boolean baseline) {
        if (baseline || selected == null) {
            asSystem(() -> files.forEach(file -> recordVersion(file, batch.getId())));
        }
        boolean reachesTenants = !baseline && changes.stream().anyMatch(TenantFileChange::reachesTenants);
        List<Long> tenants = !reachesTenants ? List.of() : selected != null ? selected : runner.syncedTenantIds();
        dispatchTenants(batch, tenants);
    }

    private void dispatchTenants(SeedSyncBatch batch, List<Long> tenantIds) {
        List<SeedSyncTask> tasks = asSystem(() -> {
            List<SeedSyncTask> created = new ArrayList<>();
            for (Long tenantId : tenantIds) {
                SeedSyncTask task = new SeedSyncTask();
                task.setBatchId(batch.getId());
                task.setTenantId(tenantId);
                task.setStatus(SeedSyncTaskStatus.PENDING);
                task.setAttempt(0);
                task.setId(taskService.createOne(task));
                created.add(task);
            }
            return created;
        });
        runner.finishBatchIfDone(batch.getId());
        if (tasks.isEmpty()) {
            log.info("Seed sync batch {}: no tenant to bring up to date", batch.getId());
            return;
        }
        if (publisher.enabled()) {
            tasks.forEach(task -> publisher.publish(new TenantSeedSyncMessage(batch.getId(), task.getId(),
                    task.getTenantId())));
            log.info("Seed sync batch {}: {} tenant task(s) handed to the MQ", batch.getId(), tasks.size());
        } else {
            log.info("Seed sync batch {}: no MQ topic configured, running {} tenant task(s) here", batch.getId(),
                    tasks.size());
            tasks.forEach(task -> runner.runTask(task.getId()));
        }
    }

    /**
     * Make the loaded rows take effect: clear what was cached from the old ones, and have every instance
     * rebuild what it built from them at startup.
     *
     * @return what went wrong, or null
     */
    private String refreshDerivedState() {
        List<String> problems = new ArrayList<>();
        for (String prefix : DERIVED_CACHE_PREFIXES) {
            try {
                long removed = cacheService.clearByPrefix(prefix);
                log.info("Seed sync cleared {} cached {}* key(s)", removed, prefix);
            } catch (RuntimeException e) {
                log.error("Seed sync could not clear the {}* cache", prefix, e);
                problems.add("Loaded, but the " + prefix + "* cache could not be cleared: " + messageOf(e));
            }
        }
        try {
            broadcaster.publish(ClusterEvents.PLATFORM_SEED_SYNCED);
        } catch (RuntimeException e) {
            log.error("Seed sync could not notify the other instances", e);
            problems.add("Loaded, but the instances could not be told to rebuild their permission indexes: "
                    + messageOf(e));
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    // ─────────────────────────── internals ───────────────────────────

    private SeedManifest requireManifest() {
        SeedManifest manifest = manifestProvider.getIfAvailable();
        if (manifest == null) {
            throw new BusinessException("The application ships no seed manifest.");
        }
        return manifest;
    }

    private List<SeedFileState> classify(SeedManifest manifest) {
        List<SeedFile> files = allFiles(manifest);
        List<SeedFileVersion> versions = files.isEmpty() ? List.of() : versionService.searchList(
                new Filters().in(SeedFileVersion::getFileName, files.stream().map(SeedFile::file).toList()));
        return SeedFileStates.classify(manifest, files, checksums(files), versions, buildTime);
    }

    /**
     * Platform-global files, then the platform tenant's, which may refer to them, then the tenant files —
     * each in load order.
     */
    private static List<SeedFile> allFiles(SeedManifest manifest) {
        List<SeedFile> out = new ArrayList<>();
        for (SeedLevel level : List.of(SeedLevel.PLATFORM_GLOBAL, SeedLevel.PLATFORM_TENANT, SeedLevel.TENANT)) {
            for (String name : manifest.loadOrder(level)) {
                manifest.file(name).ifPresent(out::add);
            }
        }
        return out;
    }

    private Map<String, String> checksums(List<SeedFile> files) {
        Map<String, String> known = checksums;
        if (known == null) {
            Map<String, String> computed = new LinkedHashMap<>();
            for (SeedFile file : files) {
                computed.put(file.file(), sha256(file.path()));
            }
            known = Map.copyOf(computed);
            checksums = known;
        }
        return known;
    }

    static String sha256(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(in.readAllBytes()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + classpathLocation, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String syncBlocker(List<SeedFileState> files, SeedSyncBatch running) {
        if (running != null) {
            return "A seed sync is already running. Wait for it to finish.";
        }
        List<String> rolledBack = names(files, State.ROLLED_BACK);
        if (!rolledBack.isEmpty()) {
            return rollbackMessage(rolledBack);
        }
        if (count(files, State.PENDING) == 0) {
            return "Every seed file is already synced.";
        }
        return null;
    }

    private static String rollbackMessage(List<String> files) {
        return "The running application carries seed files older than the data already synced: "
                + String.join(", ", files) + ". An earlier release appears to be deployed; "
                + "syncing would overwrite newer data. Deploy the current release first.";
    }

    private SeedSyncBatch createBatch(SeedSyncTriggerType triggerType, Long retryOf, List<SeedFileState> platform,
                                      List<TenantFileChange> changes, List<Long> selected) {
        SeedSyncBatch batch = new SeedSyncBatch();
        batch.setStatus(SeedSyncStatus.RUNNING);
        batch.setTriggerType(triggerType);
        batch.setRetryOfBatchId(retryOf);
        batch.setFileNames(String.join(",", platform.stream().map(SeedFileState::file).toList()));
        batch.setTotalFiles(platform.size());
        batch.setLoadedFiles(0);
        List<TenantFileChange> reaching = changes.stream().filter(TenantFileChange::reachesTenants).toList();
        batch.setTenantFileNames(String.join(",", reaching.stream().map(TenantFileChange::file).toList()));
        batch.setTenantChanges(JsonUtils.objectToString(reaching));
        batch.setSelectedTenantIds(selected == null ? null
                : String.join(",", selected.stream().map(String::valueOf).toList()));
        batch.setAppVersion(appVersion);
        batch.setBuildTime(buildTime);
        batch.setStartTime(LocalDateTime.now());
        batch.setId(batchService.createOne(batch));
        return batch;
    }

    private static List<SeedFileState> pendingOf(List<SeedFileState> files) {
        return files.stream().filter(f -> f.state() == State.PENDING).toList();
    }

    private boolean platformStepRunning() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(cacheService.getKeyPath(LOCK_KEY)));
    }

    /**
     * The batch in progress: one still marked running whose platform step holds the lock, or whose tenants
     * are not all done. A batch marked running with neither was left by an instance that stopped; the next
     * sync closes it.
     */
    private Optional<SeedSyncBatch> runningBatch() {
        List<SeedSyncBatch> running = batchService.searchList(
                new Filters().eq(SeedSyncBatch::getStatus, SeedSyncStatus.RUNNING));
        boolean locked = platformStepRunning();
        return running.stream()
                .filter(batch -> locked || runner.hasUnfinishedTasks(batch.getId()))
                .findFirst();
    }

    /**
     * Runs under the lock. A batch still marked running is either still bringing tenants up to date — then
     * no new batch may start — or was left behind: one whose tasks are all done is closed on their counts,
     * one that never got past its platform step is marked failed.
     */
    private void closeStaleBatches() {
        for (SeedSyncBatch batch : batchService.searchList(
                new Filters().eq(SeedSyncBatch::getStatus, SeedSyncStatus.RUNNING))) {
            if (runner.hasUnfinishedTasks(batch.getId())) {
                throw new BusinessException("A seed sync is still bringing tenants up to date. Wait for it to finish.");
            }
            if (taskService.exist(new Filters().eq(SeedSyncTask::getBatchId, batch.getId()))) {
                runner.finishBatchIfDone(batch.getId());
            } else {
                batch.setStatus(SeedSyncStatus.FAILED);
                batch.setEndTime(LocalDateTime.now());
                batch.setErrorMessage("Interrupted: the instance running this batch stopped before it finished.");
                batchService.updateOne(batch);
            }
        }
    }

    private void recordVersion(SeedFileState file, Long batchId) {
        SeedFileVersion version = versionService.searchOne(new Filters()
                        .eq(SeedFileVersion::getFileName, file.file())
                        .eq(SeedFileVersion::getChecksum, file.checksum()))
                .orElseGet(SeedFileVersion::new);
        version.setFileName(file.file());
        version.setChecksum(file.checksum());
        version.setAppVersion(appVersion);
        version.setBuildTime(buildTime);
        version.setChangelog(file.changelog());
        version.setRowKeys(JsonUtils.objectToString(preDataService.rowKeysOf(file.level().getDataDir(), file.file())));
        version.setSyncedTime(LocalDateTime.now());
        version.setBatchId(batchId);
        if (version.getId() == null) {
            versionService.createOne(version);
        } else {
            versionService.updateOne(version);
        }
    }

    private void updateBatch(Long batchId, Consumer<SeedSyncBatch> change) {
        SeedSyncBatch patch = new SeedSyncBatch();
        patch.setId(batchId);
        change.accept(patch);
        batchService.updateOne(patch);
    }

    private void releaseLock(String lockKey, String token) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("Could not release the seed sync lock; it expires on its own", e);
        }
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static int count(List<SeedFileState> files, State state) {
        return (int) files.stream().filter(f -> f.state() == state).count();
    }

    private static List<String> names(List<SeedFileState> files, State state) {
        return files.stream().filter(f -> f.state() == state).map(SeedFileState::file).toList();
    }

    private static String join(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "; " + b;
    }
}
