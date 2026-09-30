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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.broadcast.ClusterBroadcaster;
import io.softa.framework.orm.broadcast.ClusterEvents;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.seed.PlatformSeedState;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.metadata.entity.SeedFileVersion;
import io.softa.starter.metadata.entity.SeedSyncBatch;
import io.softa.starter.metadata.entity.SeedSyncTask;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.enums.SeedSyncScope;
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
    /**
     * The models whose rows decide which modules a plan entitles — so which packages its tenants are due.
     * A sync that loads them checks every tenant for packages it never had.
     */
    private static final Set<String> PLAN_MODELS = Set.of("Plan", "PlanEntitlement");
    private static final Set<SeedLevel> PLATFORM_LEVELS = Set.of(SeedLevel.PLATFORM_GLOBAL, SeedLevel.PLATFORM_TENANT);

    private final ObjectProvider<SeedManifest> manifestProvider;
    private final SysPreDataService preDataService;
    private final SeedFileVersionService versionService;
    private final SeedSyncBatchService batchService;
    private final SeedSyncTaskService taskService;
    private final TenantSeedSyncRunner runner;
    private final TenantSeedScope scope;
    private final TenantSeedSyncPublisher publisher;
    private final CacheService cacheService;
    private final StringRedisTemplate redisTemplate;
    private final ClusterBroadcaster broadcaster;
    private final String appVersion;
    private final LocalDateTime buildTime;
    /** How long a tenant task may wait or run before it is failed as timed out. */
    private final Duration taskTimeout;

    /** File name → SHA-256. The classpath does not change while the application runs. */
    private volatile Map<String, String> checksums;

    public SeedSyncService(ObjectProvider<SeedManifest> manifestProvider,
                           SysPreDataService preDataService,
                           SeedFileVersionService versionService,
                           SeedSyncBatchService batchService,
                           SeedSyncTaskService taskService,
                           TenantSeedSyncRunner runner,
                           TenantSeedScope scope,
                           TenantSeedSyncPublisher publisher,
                           CacheService cacheService,
                           StringRedisTemplate redisTemplate,
                           ClusterBroadcaster broadcaster,
                           ObjectProvider<BuildProperties> buildProperties,
                           @Value("${softa.seed-sync.task-timeout:30m}") Duration taskTimeout) {
        this.taskTimeout = taskTimeout;
        this.manifestProvider = manifestProvider;
        this.preDataService = preDataService;
        this.versionService = versionService;
        this.batchService = batchService;
        this.taskService = taskService;
        this.runner = runner;
        this.scope = scope;
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
            return new SeedSyncStatusView(List.of(), 0, 0, false, 0, 0, 0, List.of(), 0, 0, null, null, null, null, null, null, false,
                    "The application ships no seed manifest.", null, appVersion, buildTime);
        }
        return asSystem(() -> {
            List<SeedFileState> files = classify(manifest);
            SeedSyncBatch running = runningBatch().orElse(null);
            String blockReason = syncBlocker(files, running);
            Integer finishedTenants = running == null || running.getTotalTenants() == null ? null
                    : nullToZero(running.getSucceededTenants()) + nullToZero(running.getFailedTenants())
                    + nullToZero(running.getSkippedTenants());
            boolean baseline = tenantBaseline(manifest);
            // Worked out only between syncs: the page polls while one runs, and a running one has decided.
            List<TenantFileImpact> impacts = running != null || baseline ? List.of() : impactsOf(manifest, files);
            long affected = impacts.stream().anyMatch(i -> !i.onlyNewTenants()) ? runner.syncedTenantIds().size() : 0;
            int untracedTenants = running != null ? 0 : untracedTenantIds().size();
            long untracedPlatform = running != null ? 0 : untracedPlatformBindings();
            String traceBlockReason = running != null ? "A seed sync is running. Trace once it finishes."
                    : traceBlocker(files);
            return new SeedSyncStatusView(files, count(files, State.PENDING), count(files, State.ROLLED_BACK),
                    baseline,
                    (int) pendingOf(files).stream().filter(f -> PLATFORM_LEVELS.contains(f.level())).count(),
                    (int) pendingOf(files).stream().filter(f -> f.level() == SeedLevel.TENANT).count(),
                    affected, impacts, untracedTenants, untracedPlatform, traceBlockReason,
                    running == null ? null : running.getId(),
                    running == null ? null : running.getLoadedFiles(),
                    running == null ? null : running.getTotalFiles(),
                    finishedTenants,
                    running == null ? null : running.getTotalTenants(),
                    blockReason == null, blockReason, creationBlocker(files), appVersion, buildTime);
        });
    }

    /** What each pending tenant file would do to the tenants that have it, from its rows against its last version. */
    private List<TenantFileImpact> impactsOf(SeedManifest manifest, List<SeedFileState> files) {
        List<SeedFileState> pending = pendingOf(files).stream().filter(f -> f.level() == SeedLevel.TENANT).toList();
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Long> synced = runner.syncedTenantIds();
        List<TenantFileImpact> impacts = new ArrayList<>();
        for (SeedFileState file : pending) {
            TenantFileChange change = changeOf(file);
            SeedPushScope push = manifest.file(file.file()).map(SeedFile::push).orElse(SeedPushScope.NONE);
            long holders = synced.isEmpty() ? 0 : preDataService.getDistinctFieldValue(SysPreData::getTenantId,
                    new Filters().eq(SysPreData::getSourceFile, file.file()).in(SysPreData::getTenantId, synced)).size();
            boolean removesColumns = push == SeedPushScope.INVALID_COLUMNS && !change.removed().isEmpty();
            impacts.add(new TenantFileImpact(file.file(), change.added().size(), change.removed().size(), push,
                    holders, change.added().isEmpty() && !removesColumns));
        }
        return impacts;
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
                PlatformStep step = loadPlatformFiles(batch, platform, lockKey);
                if (step.succeeded()) {
                    startTenantStep(batch, tenant, changes, selected, baseline, step.plansChanged());
                }
            });
        });
    }

    /**
     * Load the named platform files of one level, whether or not they changed — a platform file is what its
     * rows are, so loading it again puts back what someone edited. Otherwise it is a sync of those files: a
     * batch of its own, their versions recorded, the permissions and entitlements cached from the old rows
     * cleared, and, when plan rows were among them, tenants given the files their plan now entitles them to.
     * Tenant files are out of reach here: a tenant is only ever given what it does not have.
     *
     * @param fileNames the files, each in the manifest at {@code level}
     * @param level     {@link SeedLevel#PLATFORM_GLOBAL} or {@link SeedLevel#PLATFORM_TENANT}
     * @return the batch now running in the background
     */
    public SeedSyncBatch loadFiles(List<String> fileNames, SeedLevel level) {
        Assert.isTrue(PLATFORM_LEVELS.contains(level), "Only platform files are loaded by name, not {0} ones.", level);
        SeedManifest manifest = requireManifest();
        Set<String> requested = new LinkedHashSet<>(fileNames);
        return withLock(lockKey -> {
            List<SeedFileState> all = classify(manifest);
            List<String> problems = new ArrayList<>();
            for (String name : requested) {
                SeedFileState file = all.stream().filter(f -> f.file().equals(name)).findFirst().orElse(null);
                if (file == null) {
                    problems.add(name + " is not in the seed manifest");
                } else if (file.level() != level) {
                    problems.add(name + " is a " + file.level() + " file, not a " + level + " one");
                } else if (file.state() == State.ROLLED_BACK) {
                    problems.add(name + " is older than the data already synced from it; deploy the current "
                            + "release first");
                }
            }
            if (!problems.isEmpty()) {
                throw new BusinessException("Nothing was loaded: " + String.join("; ", problems) + ".");
            }
            // In manifest order, whatever order they were named in: a file is loaded after those it depends on.
            List<SeedFileState> files = all.stream().filter(f -> requested.contains(f.file())).toList();
            SeedSyncBatch batch = createBatch(SeedSyncTriggerType.API, null, files, List.of(), null);
            return new Plan(batch, () -> {
                PlatformStep step = loadPlatformFiles(batch, files, lockKey);
                if (step.succeeded()) {
                    startTenantStep(batch, List.of(), List.of(), null, false, step.plansChanged());
                }
            });
        }).orElseThrow();
    }

    // ─────────────────────────── tracing ───────────────────────────

    /**
     * Once, after the release that starts recording which file brought each row: trace every binding that
     * does not record its file yet to the file that declares its row — or mark it untraced — and give each
     * tenant set up before what it is due and does not have: the files it never had, whole, and the rows of
     * the files it has that it has no binding for. A row the tenant already has a binding for is left as it
     * is; one it has under the same business key is claimed; one that would break a unique key fails the
     * tenant, for someone to decide. The shared rows are traced first, then one task per tenant, through the
     * MQ. Refused while seed files are pending: tracing reads the files as synced.
     *
     * @param tenantIds limit the tracing to these tenants, to try it on a few first; the shared rows are then
     *                  left for the tracing that reaches every tenant. Null or empty for every tenant
     * @return the batch now running, or empty when nothing is left to trace
     */
    public Optional<SeedSyncBatch> trace(List<Long> tenantIds) {
        List<Long> selected = tenantIds == null || tenantIds.isEmpty() ? null : List.copyOf(new LinkedHashSet<>(tenantIds));
        SeedManifest manifest = requireManifest();
        return withLock(lockKey -> {
            String blocker = traceBlocker(classify(manifest));
            if (blocker != null) {
                throw new BusinessException(blocker);
            }
            List<Long> untraced = untracedTenantIds();
            List<Long> tenants = untraced;
            if (selected != null) {
                List<Long> unknown = selected.stream().filter(id -> !untraced.contains(id)).toList();
                if (!unknown.isEmpty()) {
                    throw new BusinessException("Tenants " + unknown + " have nothing to trace: they are not active "
                            + "or suspended, or their seed data is traced already.");
                }
                tenants = selected;
            }
            if (tenants.isEmpty() && (selected != null || untracedPlatformBindings() == 0)) {
                return null;
            }
            Map<Long, List<String>> due = new LinkedHashMap<>();
            tenants.forEach(tenantId -> due.put(tenantId, scope.dueFiles(tenantId)));
            Set<String> dueFiles = new LinkedHashSet<>();
            due.values().forEach(dueFiles::addAll);
            SeedSyncBatch batch = newBatch(SeedSyncTriggerType.INITIALIZE);
            batch.setSelectedTenantIds(selected == null ? null
                    : String.join(",", selected.stream().map(String::valueOf).toList()));
            batch.setTenantFileNames(String.join(",", dueFiles));
            describe(batch, selected == null, !due.isEmpty(), dueFiles.size());
            batch.setId(batchService.createOne(batch));
            return new Plan(batch, () -> {
                int platform = selected == null ? asSystem(() -> tracePlatform()) : 0;
                log.info("Seed sync batch {}: {} shared binding(s) traced; {} tenant(s) to trace", batch.getId(),
                        platform, due.size());
                Map<Long, List<TenantFileChange>> own = new LinkedHashMap<>();
                due.forEach((tenantId, files) -> own.put(tenantId, wholeFileChanges(files)));
                dispatchTenants(batch, own);
            });
        });
    }

    /** What {@link #trace} would do to each tenant it reaches — the given ones, or every one. */
    public List<TenantTracePreview> tracePreview(List<Long> tenantIds) {
        requireManifest();
        return asSystem(() -> {
            List<Long> untraced = untracedTenantIds();
            return scope.tracePreview(tenantIds == null || tenantIds.isEmpty() ? untraced
                    : tenantIds.stream().filter(untraced::contains).distinct().toList());
        });
    }

    /** The shared rows' bindings and the platform tenant's, each against the files of its own level. */
    private int tracePlatform() {
        int traced = 0;
        for (TenantSeedFileResult r : preDataService.traceSources(scope.fileOfRowKey(SeedLevel.PLATFORM_GLOBAL), null)) {
            traced += r.traced();
        }
        for (TenantSeedFileResult r : preDataService.traceSources(scope.fileOfRowKey(SeedLevel.PLATFORM_TENANT),
                BaseConstant.PLATFORM_TENANT_ID)) {
            traced += r.traced();
        }
        return traced;
    }

    /**
     * Active or suspended tenants whose bindings do not all record their file — or that have none, set up
     * before bindings were written at all.
     */
    private List<Long> untracedTenantIds() {
        List<Long> synced = runner.syncedTenantIds();
        if (synced.isEmpty()) {
            return List.of();
        }
        Set<Long> untraced = new LinkedHashSet<>(preDataService.getDistinctFieldValue(SysPreData::getTenantId,
                new Filters().in(SysPreData::getTenantId, synced).isNotSet(SysPreData::getSourceFile)));
        Set<Long> withBindings = new LinkedHashSet<>(preDataService.getDistinctFieldValue(SysPreData::getTenantId,
                new Filters().in(SysPreData::getTenantId, synced)));
        return synced.stream().filter(id -> untraced.contains(id) || !withBindings.contains(id)).toList();
    }

    private long untracedPlatformBindings() {
        return preDataService.count(new Filters().isNotSet(SysPreData::getTenantId).isNotSet(SysPreData::getSourceFile))
                + preDataService.count(new Filters().eq(SysPreData::getTenantId, BaseConstant.PLATFORM_TENANT_ID)
                        .isNotSet(SysPreData::getSourceFile));
    }

    /** Why seed data cannot be traced now, or null: every file must be synced, as the tracing reads them. */
    private String traceBlocker(List<SeedFileState> files) {
        List<String> rolledBack = names(files, State.ROLLED_BACK);
        if (!rolledBack.isEmpty()) {
            return rollbackMessage(rolledBack);
        }
        if (count(files, State.PENDING) > 0) {
            return "Sync the pending seed files first: seed data is traced against the files as synced.";
        }
        return null;
    }

    /** Whether the application ships a seed manifest, without which nothing is synced. */
    public boolean hasManifest() {
        return manifestProvider.getIfAvailable() != null;
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
     * Re-run tenants a finished batch failed on, each with the tenant files its task applied, as a new
     * ManualRetry batch.
     *
     * @param tenantIds the failed tenants to re-run; null or empty for every one
     * @return the new batch, or empty when none of them failed in the batch
     */
    public Optional<SeedSyncBatch> retry(Long batchId, List<Long> tenantIds) {
        requireManifest();
        return withLock(lockKey -> {
            SeedSyncBatch original = batchService.getById(batchId)
                    .orElseThrow(() -> new BusinessException("Seed sync batch " + batchId + " does not exist."));
            Map<Long, List<TenantFileChange>> failedTenants = new LinkedHashMap<>();
            Filters failed = new Filters()
                    .eq(SeedSyncTask::getBatchId, batchId)
                    .eq(SeedSyncTask::getStatus, SeedSyncTaskStatus.FAILED);
            if (tenantIds != null && !tenantIds.isEmpty()) {
                failed.in(SeedSyncTask::getTenantId, tenantIds);
            }
            taskService.searchList(failed)
                    .forEach(task -> failedTenants.put(task.getTenantId(), StringUtils.isBlank(task.getChanges()) ? null
                            : JsonUtils.stringToObject(task.getChanges(), new TypeReference<List<TenantFileChange>>() {})));
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
            describe(batch, false, true, namesOf(original.getTenantFileNames()).size());
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
     * How the platform step went.
     *
     * @param succeeded    whether it did — the tenants are not touched when it did not
     * @param plansChanged whether it loaded plan rows, which may entitle tenants to packages they never had
     */
    private record PlatformStep(boolean succeeded, boolean plansChanged) {}

    /**
     * The platform step: load the pending platform files, then make them take effect. Whether any of them
     * carried plan rows is read off the row keys recorded for their versions, not from the files again.
     */
    private PlatformStep loadPlatformFiles(SeedSyncBatch batch, List<SeedFileState> files, String lockKey) {
        int loaded = 0;
        String error = null;
        Set<String> loadedKeys = new LinkedHashSet<>();
        for (SeedFileState file : files) {
            try {
                if (file.level() == SeedLevel.PLATFORM_TENANT) {
                    preDataService.loadPrePlatformData(List.of(file.file()));
                } else {
                    preDataService.loadPreSystemData(List.of(file.file()));
                }
                loadedKeys.addAll(asSystem(() -> recordVersion(file, batch.getId())));
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
            return new PlatformStep(false, false);
        }
        boolean plansChanged = loadedKeys.stream()
                .anyMatch(key -> PLAN_MODELS.stream().anyMatch(model -> key.startsWith(model + "/")));
        log.info("Seed sync batch {}: {} platform file(s) loaded{}", batch.getId(), loaded,
                plansChanged ? ", plans among them" : "");
        return new PlatformStep(true, plansChanged);
    }

    /**
     * The tenant step. A sync that reaches every tenant records the tenant files' versions — the change is
     * now this batch's to deliver, to a failed tenant through a retry — and a limited one leaves them
     * pending. Then each tenant the change reaches gets a task. When the platform step loaded plans, a plan
     * may now entitle packages its tenants never had, so each tenant is also checked for files it is due and
     * never had, loaded whole in the same task. A tenant's own plan or country change is handled when it
     * happens ({@link #reconcile}); only a change to the plans themselves is found here.
     */
    private void startTenantStep(SeedSyncBatch batch, List<SeedFileState> files, List<TenantFileChange> changes,
                                 List<Long> selected, boolean baseline, boolean plansChanged) {
        if (baseline || selected == null) {
            asSystem(() -> files.forEach(file -> recordVersion(file, batch.getId())));
        }
        List<TenantFileChange> delivered = baseline ? List.of()
                : changes.stream().filter(TenantFileChange::reachesTenants).toList();
        Map<Long, List<TenantFileChange>> own = new LinkedHashMap<>();
        for (Long tenantId : selected != null ? selected : runner.syncedTenantIds()) {
            List<TenantFileChange> whole = plansChanged ? wholeFileChanges(scope.missingFiles(tenantId)) : List.of();
            if (!whole.isEmpty()) {
                own.put(tenantId, merge(delivered, whole));
            } else if (!delivered.isEmpty()) {
                own.put(tenantId, null);
            }
        }
        dispatchTenants(batch, own);
    }

    /**
     * Give tenants the tenant files they are due and never had — their plan now entitles more, or they have
     * a company in a new country — as a batch of its own. Only active or suspended tenants whose bindings
     * all record their file; nothing when a sync's platform step is running, whose tenant step does the same.
     *
     * @return the batch, or empty when no tenant was missing anything
     */
    public Optional<SeedSyncBatch> reconcile(List<Long> tenantIds, SeedSyncTriggerType triggerType) {
        if (manifestProvider.getIfAvailable() == null || platformStepRunning()) {
            return Optional.empty();
        }
        List<Long> synced = runner.syncedTenantIds();
        Map<Long, List<TenantFileChange>> own = new LinkedHashMap<>();
        for (Long tenantId : tenantIds) {
            if (synced.contains(tenantId)) {
                List<TenantFileChange> whole = wholeFileChanges(scope.missingFiles(tenantId));
                if (!whole.isEmpty()) {
                    own.put(tenantId, whole);
                }
            }
        }
        if (own.isEmpty()) {
            return Optional.empty();
        }
        SeedSyncBatch batch = asSystem(() -> {
            Set<String> fileNames = new LinkedHashSet<>();
            own.values().forEach(list -> list.forEach(change -> fileNames.add(change.file())));
            SeedSyncBatch created = newBatch(triggerType);
            created.setTenantFileNames(String.join(",", fileNames));
            describe(created, false, true, fileNames.size());
            created.setId(batchService.createOne(created));
            return created;
        });
        log.info("Seed sync batch {} ({}): {} tenant(s) due files they never had", batch.getId(), triggerType,
                own.size());
        dispatchTenants(batch, own);
        return Optional.of(batch);
    }

    /**
     * Record a tenant's setup as a batch of its own, around the load that does it — so the tenant's first
     * seed data shows up among its syncs, with the files it was given.
     *
     * @return the task to finish with {@link #finishProvision}
     */
    public SeedSyncTask startProvision(Long tenantId, List<String> files) {
        return asSystem(() -> {
            SeedSyncBatch batch = newBatch(SeedSyncTriggerType.PROVISION);
            batch.setTenantFileNames(String.join(",", files));
            describe(batch, false, true, files.size());
            batch.setId(batchService.createOne(batch));
            SeedSyncTask task = new SeedSyncTask();
            task.setBatchId(batch.getId());
            task.setTenantId(tenantId);
            task.setTenantCode(scope.tenantCodes(List.of(tenantId)).get(tenantId));
            task.setStatus(SeedSyncTaskStatus.RUNNING);
            task.setAttempt(1);
            task.setStartTime(LocalDateTime.now());
            task.setId(taskService.createOne(task));
            runner.recordLatestTask(tenantId, task.getId());
            return task;
        });
    }

    /** Close a setup recorded by {@link #startProvision}: succeeded, or failed with why. */
    public void finishProvision(SeedSyncTask task, RuntimeException failure) {
        asSystem(() -> {
            SeedSyncTask patch = new SeedSyncTask();
            patch.setId(task.getId());
            patch.setStatus(failure == null ? SeedSyncTaskStatus.SUCCEEDED : SeedSyncTaskStatus.FAILED);
            patch.setEndTime(LocalDateTime.now());
            if (failure == null) {
                // Its files were loaded whole: every row of them, nested ones included, is new to the tenant.
                String files = batchService.getById(task.getBatchId()).map(SeedSyncBatch::getTenantFileNames).orElse(null);
                patch.setNewRowCount(preDataService.rowCountOf(SeedLevel.TENANT.getDataDir(), namesOf(files)));
            }
            patch.setErrorSummary(failure == null ? null : truncate(messageOf(failure)));
            taskService.updateOne(patch);
        });
        runner.finishBatchIfDone(task.getBatchId());
    }

    /** Loading a file whole: every row of it counts as added. */
    private List<TenantFileChange> wholeFileChanges(List<String> files) {
        return files.stream()
                .map(file -> new TenantFileChange(file,
                        List.copyOf(preDataService.rowKeysOf(SeedLevel.TENANT.getDataDir(), file)), List.of()))
                .toList();
    }

    /** A batch's changes plus files loaded whole; a file in both is loaded whole. */
    private static List<TenantFileChange> merge(List<TenantFileChange> delivered, List<TenantFileChange> whole) {
        Map<String, TenantFileChange> byFile = new LinkedHashMap<>();
        delivered.forEach(change -> byFile.put(change.file(), change));
        whole.forEach(change -> byFile.put(change.file(), change));
        return List.copyOf(byFile.values());
    }

    /** A batch's comma-separated file names as a list; empty for none. */
    private static List<String> namesOf(String fileNames) {
        return StringUtils.isBlank(fileNames) ? List.of() : List.of(fileNames.split(","));
    }

    /**
     * Record what the batch covers, for the sync history to say at a glance: whether it loads platform files
     * (or, tracing, the shared bindings), whether it reaches tenants, and how many tenant files it brings them.
     */
    static void describe(SeedSyncBatch batch, boolean platform, boolean tenants, int tenantFiles) {
        batch.setTenantFileCount(tenantFiles);
        batch.setScope(platform && tenants ? SeedSyncScope.PLATFORM_AND_TENANTS
                : platform ? SeedSyncScope.PLATFORM : SeedSyncScope.TENANTS);
    }

    private SeedSyncBatch newBatch(SeedSyncTriggerType triggerType) {
        SeedSyncBatch batch = new SeedSyncBatch();
        batch.setStatus(SeedSyncStatus.RUNNING);
        batch.setTriggerType(triggerType);
        batch.setTotalFiles(0);
        batch.setLoadedFiles(0);
        batch.setAppVersion(appVersion);
        batch.setBuildTime(buildTime);
        batch.setStartTime(LocalDateTime.now());
        return batch;
    }

    /**
     * Give each tenant a task and hand the tasks out.
     *
     * @param own per tenant, its own changes — or null to take the batch's
     */
    private void dispatchTenants(SeedSyncBatch batch, Map<Long, List<TenantFileChange>> own) {
        List<SeedSyncTask> tasks = asSystem(() -> {
            List<SeedSyncTask> created = new ArrayList<>();
            Map<Long, String> codes = scope.tenantCodes(own.keySet());
            own.forEach((tenantId, changes) -> {
                SeedSyncTask task = new SeedSyncTask();
                task.setBatchId(batch.getId());
                task.setTenantId(tenantId);
                task.setTenantCode(codes.get(tenantId));
                task.setStatus(SeedSyncTaskStatus.PENDING);
                task.setAttempt(0);
                task.setChanges(changes == null ? null : JsonUtils.objectToString(changes));
                task.setId(taskService.createOne(task));
                runner.recordLatestTask(tenantId, task.getId());
                created.add(task);
            });
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
        describe(batch, !platform.isEmpty(), !changes.isEmpty(), reaching.size());
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
        runner.failStaleTasks(taskTimeout);
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

    /** Fail the tenant tasks that have not finished within the configured time. */
    public int failStaleTasks() {
        return runner.failStaleTasks(taskTimeout);
    }

    /** Record the file's version and return its row keys. */
    private Set<String> recordVersion(SeedFileState file, Long batchId) {
        SeedFileVersion version = versionService.searchOne(new Filters()
                        .eq(SeedFileVersion::getFileName, file.file())
                        .eq(SeedFileVersion::getChecksum, file.checksum()))
                .orElseGet(SeedFileVersion::new);
        version.setFileName(file.file());
        version.setChecksum(file.checksum());
        version.setAppVersion(appVersion);
        version.setBuildTime(buildTime);
        version.setChangelog(file.changelog());
        Set<String> rowKeys = preDataService.rowKeysOf(file.level().getDataDir(), file.file());
        version.setRowKeys(JsonUtils.objectToString(rowKeys));
        version.setSyncedTime(LocalDateTime.now());
        version.setBatchId(batchId);
        if (version.getId() == null) {
            versionService.createOne(version);
        } else {
            versionService.updateOne(version);
        }
        return rowKeys;
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
