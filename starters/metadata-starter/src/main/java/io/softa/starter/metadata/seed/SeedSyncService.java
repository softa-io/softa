package io.softa.starter.metadata.seed;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.broadcast.ClusterBroadcaster;
import io.softa.framework.orm.broadcast.ClusterEvents;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.seed.PlatformSeedState;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.entity.SeedFileVersion;
import io.softa.starter.metadata.entity.SeedSyncBatch;
import io.softa.starter.metadata.enums.SeedSyncStatus;
import io.softa.starter.metadata.seed.SeedFileState.State;
import io.softa.starter.metadata.service.SeedFileVersionService;
import io.softa.starter.metadata.service.SeedSyncBatchService;
import io.softa.starter.metadata.service.SysPreDataService;

/**
 * Applies the platform seed files of the running release — the shared rows every tenant reads, and the
 * platform tenant's own — when the platform admin asks, never at deploy or startup.
 *
 * <p>A sync loads only the files whose content differs from the version the database last loaded, in
 * manifest order, in the background; the caller gets the batch and follows its progress. Once anything
 * is loaded, the cached permission snapshots and entitlements of every tenant are cleared and every
 * instance is told to rebuild its in-memory permission indexes, so the new rows take effect without a
 * restart. One sync runs at a time across all instances.
 *
 * <p>Rows removed from a file are not deleted: a file does not record which rows it loaded before.
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
    private static final String TENANT_MODEL = "TenantInfo";
    private static final int ERROR_LIMIT = 4000;

    private final ObjectProvider<SeedManifest> manifestProvider;
    private final SysPreDataService preDataService;
    private final SeedFileVersionService versionService;
    private final SeedSyncBatchService batchService;
    private final CacheService cacheService;
    private final StringRedisTemplate redisTemplate;
    private final ClusterBroadcaster broadcaster;
    private final ModelService<?> modelService;
    private final String appVersion;
    private final LocalDateTime buildTime;

    /** File name → SHA-256. The classpath does not change while the application runs. */
    private volatile Map<String, String> checksums;

    public SeedSyncService(ObjectProvider<SeedManifest> manifestProvider,
                           SysPreDataService preDataService,
                           SeedFileVersionService versionService,
                           SeedSyncBatchService batchService,
                           CacheService cacheService,
                           StringRedisTemplate redisTemplate,
                           ClusterBroadcaster broadcaster,
                           ModelService<?> modelService,
                           ObjectProvider<BuildProperties> buildProperties) {
        this.manifestProvider = manifestProvider;
        this.preDataService = preDataService;
        this.versionService = versionService;
        this.batchService = batchService;
        this.cacheService = cacheService;
        this.redisTemplate = redisTemplate;
        this.broadcaster = broadcaster;
        this.modelService = modelService;
        BuildProperties build = buildProperties.getIfAvailable();
        this.appVersion = build == null ? null : build.getVersion();
        this.buildTime = build == null || build.getTime() == null
                ? null : LocalDateTime.ofInstant(build.getTime(), ZoneId.systemDefault());
    }

    // ─────────────────────────── status ───────────────────────────

    public SeedSyncStatusView status() {
        SeedManifest manifest = manifestProvider.getIfAvailable();
        if (manifest == null) {
            return new SeedSyncStatusView(List.of(), 0, 0, 0, null, null, null, false,
                    "The application ships no seed manifest.", null, appVersion, buildTime);
        }
        return asSystem(() -> {
            List<SeedFileState> files = classify(manifest);
            int pending = count(files, State.PENDING);
            int rolledBack = count(files, State.ROLLED_BACK);
            SeedSyncBatch running = runningBatch().orElse(null);
            String blockReason = syncBlocker(files, running);
            return new SeedSyncStatusView(files, pending, rolledBack, affectedTenants(),
                    running == null ? null : running.getId(),
                    running == null ? null : running.getLoadedFiles(),
                    running == null ? null : running.getTotalFiles(),
                    blockReason == null, blockReason, creationBlocker(files, running), appVersion, buildTime);
        });
    }

    @Override
    public Optional<String> tenantCreationBlocker() {
        SeedManifest manifest = manifestProvider.getIfAvailable();
        if (manifest == null) {
            return Optional.empty();
        }
        return asSystem(() -> Optional.ofNullable(creationBlocker(classify(manifest), runningBatch().orElse(null))));
    }

    private static String creationBlocker(List<SeedFileState> files, SeedSyncBatch running) {
        if (running != null) {
            return "Platform seed data is being synced. Create the tenant once the sync finishes.";
        }
        List<String> rolledBack = names(files, State.ROLLED_BACK);
        if (!rolledBack.isEmpty()) {
            return rollbackMessage(rolledBack);
        }
        int pending = count(files, State.PENDING);
        if (pending > 0) {
            return pending + " platform seed file(s) of this release are not synced yet. "
                    + "Sync them on the Sync Seed Data page before creating a tenant.";
        }
        return null;
    }

    // ─────────────────────────── sync ───────────────────────────

    /**
     * Start a sync of every pending platform file.
     *
     * @return the batch now running in the background, or empty when there was nothing to load
     */
    public Optional<SeedSyncBatch> sync() {
        SeedManifest manifest = manifestProvider.getIfAvailable();
        if (manifest == null) {
            throw new BusinessException("The application ships no seed manifest.");
        }
        String lockKey = cacheService.getKeyPath(LOCK_KEY);
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL))) {
            throw new BusinessException("A seed sync is already running. Wait for it to finish.");
        }
        Prepared prepared;
        try {
            prepared = asSystem(() -> {
                failInterruptedBatches();
                List<SeedFileState> files = classify(manifest);
                List<String> rolledBack = names(files, State.ROLLED_BACK);
                if (!rolledBack.isEmpty()) {
                    throw new BusinessException(rollbackMessage(rolledBack));
                }
                List<SeedFileState> pending = pendingOf(files);
                return new Prepared(createBatch(pending), pending);
            });
        } catch (RuntimeException e) {
            releaseLock(lockKey, token);
            throw e;
        }
        SeedSyncBatch batch = prepared.batch();
        if (batch == null) {
            releaseLock(lockKey, token);
            return Optional.empty();
        }
        // The loaders stamp the caller as the author of what they write, so the caller's context goes along.
        Context caller = ContextHolder.cloneContext();
        Thread.ofVirtual().name("seed-sync-" + batch.getId()).start(() -> ContextHolder.runWith(caller, () -> {
            try {
                run(batch, prepared.files(), lockKey);
            } finally {
                releaseLock(lockKey, token);
            }
        }));
        return Optional.of(batch);
    }

    private record Prepared(SeedSyncBatch batch, List<SeedFileState> files) {}

    private void run(SeedSyncBatch batch, List<SeedFileState> files, String lockKey) {
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
        String failure = error;
        asSystem(() -> updateBatch(batch.getId(), b -> {
            b.setStatus(failure == null ? SeedSyncStatus.SUCCEEDED : SeedSyncStatus.FAILED);
            b.setEndTime(LocalDateTime.now());
            b.setErrorMessage(truncate(failure));
        }));
        log.info("Seed sync batch {} ended: {} of {} file(s) loaded{}", batch.getId(), loaded, files.size(),
                failure == null ? "" : ", error: " + failure);
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

    private List<SeedFileState> classify(SeedManifest manifest) {
        List<SeedFile> files = platformFiles(manifest);
        List<SeedFileVersion> versions = files.isEmpty() ? List.of() : versionService.searchList(
                new Filters().in(SeedFileVersion::getFileName, files.stream().map(SeedFile::file).toList()));
        return SeedFileStates.classify(manifest, files, checksums(files), versions, buildTime);
    }

    /** Platform-global files first, then the platform tenant's, which may refer to them — each in load order. */
    private static List<SeedFile> platformFiles(SeedManifest manifest) {
        List<SeedFile> out = new ArrayList<>();
        for (SeedLevel level : List.of(SeedLevel.PLATFORM_GLOBAL, SeedLevel.PLATFORM_TENANT)) {
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
            return "Every platform seed file is already synced.";
        }
        return null;
    }

    private static String rollbackMessage(List<String> files) {
        return "The running application carries platform seed files older than the data already synced: "
                + String.join(", ", files) + ". An earlier release appears to be deployed; "
                + "syncing would overwrite newer data. Deploy the current release first.";
    }

    private SeedSyncBatch createBatch(List<SeedFileState> pending) {
        if (pending.isEmpty()) {
            return null;
        }
        SeedSyncBatch batch = new SeedSyncBatch();
        batch.setStatus(SeedSyncStatus.RUNNING);
        batch.setFileNames(String.join(",", pending.stream().map(SeedFileState::file).toList()));
        batch.setTotalFiles(pending.size());
        batch.setLoadedFiles(0);
        batch.setAppVersion(appVersion);
        batch.setBuildTime(buildTime);
        batch.setStartTime(LocalDateTime.now());
        batch.setId(batchService.createOne(batch));
        return batch;
    }

    private static List<SeedFileState> pendingOf(List<SeedFileState> files) {
        return files.stream().filter(f -> f.state() == State.PENDING).toList();
    }

    /**
     * The batch in progress. A batch still marked running with no lock behind it was left by an instance
     * that stopped mid-run; it is not in progress, and the next sync marks it failed.
     */
    private Optional<SeedSyncBatch> runningBatch() {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(cacheService.getKeyPath(LOCK_KEY)))) {
            return Optional.empty();
        }
        return batchService.searchOne(new Filters().eq(SeedSyncBatch::getStatus, SeedSyncStatus.RUNNING));
    }

    /** Runs under the lock, so any batch still marked running is one nobody is running. */
    private void failInterruptedBatches() {
        for (SeedSyncBatch stale : batchService.searchList(
                new Filters().eq(SeedSyncBatch::getStatus, SeedSyncStatus.RUNNING))) {
            stale.setStatus(SeedSyncStatus.FAILED);
            stale.setEndTime(LocalDateTime.now());
            stale.setErrorMessage("Interrupted: the instance running this batch stopped before it finished.");
            batchService.updateOne(stale);
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

    private long affectedTenants() {
        if (!ModelManager.existModel(TENANT_MODEL)) {
            return 0;
        }
        return modelService.count(TENANT_MODEL, new Filters().ne("status", "Closed"));
    }

    private void releaseLock(String lockKey, String token) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("Could not release the seed sync lock; it expires on its own", e);
        }
    }

    /** Own reads and writes of the sync's bookkeeping, which no tenant or role scope applies to. */
    private static <T> T asSystem(Supplier<T> action) {
        return ContextUtils.inSystemContext(action);
    }

    private static void asSystem(Runnable action) {
        ContextUtils.inSystemContext(action);
    }

    private static int count(List<SeedFileState> files, State state) {
        return (int) files.stream().filter(f -> f.state() == state).count();
    }

    private static List<String> names(List<SeedFileState> files, State state) {
        return files.stream().filter(f -> f.state() == state).map(SeedFileState::file).toList();
    }

    private static String messageOf(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return root == e ? message : e.getMessage() + " (" + message + ")";
    }

    private static String join(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "; " + b;
    }

    private static String truncate(String text) {
        return text == null || text.length() <= ERROR_LIMIT ? text : text.substring(0, ERROR_LIMIT);
    }
}
