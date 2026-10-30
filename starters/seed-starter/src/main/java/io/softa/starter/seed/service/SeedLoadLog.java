package io.softa.starter.seed.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.seed.dto.FileState;
import io.softa.starter.seed.dto.RunningBatch;
import io.softa.starter.seed.entity.SeedLoadBatch;
import io.softa.starter.seed.entity.SeedLoadRecord;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.enums.SeedLoadType;
import io.softa.starter.seed.enums.SeedRecordStatus;
import io.softa.starter.seed.manifest.SeedFile;

/**
 * The record of seed data operations — {@link SeedLoadBatch}es and their {@link SeedLoadRecord}s — and what a
 * file's state in a scope is, read off them.
 *
 * <p>Everything here is bookkeeping on shared models, so it runs in the system context. That changes no
 * transaction: a record written while a file loads lands in the load's transaction.
 */
@Component
public class SeedLoadLog {

    /** A running batch that has not moved for this long was interrupted — its instance went down. */
    static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final SeedLoadBatchService batchService;
    private final SeedLoadRecordService recordService;
    private final SeedCatalog catalog;

    public SeedLoadLog(SeedLoadBatchService batchService, SeedLoadRecordService recordService, SeedCatalog catalog) {
        this.batchService = batchService;
        this.recordService = recordService;
        this.catalog = catalog;
    }

    // ─────────────────────────── batches ───────────────────────────

    /** Open a batch, running from now. */
    public Long start(SeedLoadType type, List<Long> tenantIds, Integer totalTenants) {
        SeedLoadBatch batch = new SeedLoadBatch();
        batch.setType(type);
        batch.setStatus(SeedLoadStatus.RUNNING);
        batch.setTenantIds(tenantIds == null || tenantIds.isEmpty() ? null
                : tenantIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        batch.setTotalTenants(totalTenants);
        batch.setFinishedTenants(0);
        batch.setFailedTenants(0);
        batch.setFileCount(0);
        LocalDateTime now = LocalDateTime.now();
        batch.setStartTime(now);
        batch.setLastProgressTime(now);
        batch.setAppVersion(catalog.appVersion());
        return ContextUtils.inSystemContext(() -> batchService.createOne(batch));
    }

    /** Count a batch's progress, and show it is still moving. */
    public void progress(Long batchId, int finishedTenants, int failedTenants, int fileCount) {
        SeedLoadBatch patch = new SeedLoadBatch();
        patch.setId(batchId);
        patch.setFinishedTenants(finishedTenants);
        patch.setFailedTenants(failedTenants);
        patch.setFileCount(fileCount);
        patch.setLastProgressTime(LocalDateTime.now());
        ContextUtils.inSystemContext(() -> batchService.updateOne(patch));
    }

    /** Close a batch. */
    public void finish(Long batchId, SeedLoadStatus status, int fileCount, String errorMessage) {
        SeedLoadBatch patch = new SeedLoadBatch();
        patch.setId(batchId);
        patch.setStatus(status);
        patch.setFileCount(fileCount);
        patch.setErrorMessage(errorMessage);
        LocalDateTime now = LocalDateTime.now();
        patch.setLastProgressTime(now);
        patch.setEndTime(now);
        ContextUtils.inSystemContext(() -> batchService.updateOne(patch));
    }

    /**
     * The platform load, tenant run, reapply or initialization in progress, if any. A tenant's setup is not one of
     * them: it touches only that tenant, which no run reaches until it is set up.
     */
    public Optional<RunningBatch> running() {
        return runningBatches().stream().findFirst().map(batch -> new RunningBatch(batch.getId(), batch.getType(),
                batch.getStatus(), batch.getTotalTenants(), batch.getFinishedTenants(), batch.getFailedTenants(),
                batch.getStartTime(), batch.getLastProgressTime(), isStale(batch)));
    }

    /**
     * Refuse to start while another operation runs. One that stopped moving is closed as interrupted instead:
     * its instance went down, and running again continues where it left off.
     */
    public void requireIdle() {
        for (SeedLoadBatch batch : runningBatches()) {
            if (!isStale(batch)) {
                throw new BusinessException("A seed data operation is still running (batch {0}). Wait for it to "
                        + "finish.", String.valueOf(batch.getId()));
            }
            finish(batch.getId(), SeedLoadStatus.INTERRUPTED, batch.getFileCount() == null ? 0 : batch.getFileCount(),
                    "Interrupted: it stopped making progress. Running again continues with whatever has not succeeded.");
        }
    }

    /** Whether the one-time initialization of an existing environment has run to the end. */
    public boolean initialized() {
        return ContextUtils.inSystemContext(() -> batchService.exist(new Filters()
                .eq(SeedLoadBatch::getType, SeedLoadType.INITIALIZE)
                .in(SeedLoadBatch::getStatus, List.of(SeedLoadStatus.SUCCEEDED, SeedLoadStatus.PARTIALLY_FAILED))));
    }

    /** When the latest successful platform load ended, or null. */
    public LocalDateTime lastPlatformLoad() {
        return ContextUtils.inSystemContext(() -> batchService.searchList(new Filters()
                        .eq(SeedLoadBatch::getType, SeedLoadType.PLATFORM)
                        .eq(SeedLoadBatch::getStatus, SeedLoadStatus.SUCCEEDED)))
                .stream()
                .map(SeedLoadBatch::getEndTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private List<SeedLoadBatch> runningBatches() {
        return ContextUtils.inSystemContext(() -> batchService.searchList(
                        new Filters().eq(SeedLoadBatch::getStatus, SeedLoadStatus.RUNNING)))
                .stream()
                .filter(batch -> batch.getType() != SeedLoadType.PROVISION)
                .sorted(Comparator.comparing(SeedLoadBatch::getId).reversed())
                .toList();
    }

    private static boolean isStale(SeedLoadBatch batch) {
        LocalDateTime since = batch.getLastProgressTime() != null ? batch.getLastProgressTime() : batch.getStartTime();
        return since != null && since.isBefore(LocalDateTime.now().minus(STALE_AFTER));
    }

    // ─────────────────────────── records ───────────────────────────

    /**
     * Record one file's run in one scope.
     *
     * @param tenantId   the tenant, or null for the platform
     * @param tenantName its name, for whoever reads the record
     * @param error      why it failed, or null
     * @param start      when the run started
     */
    public void record(Long batchId, Long tenantId, String tenantName, SeedFile file, SeedRecordStatus status,
                       String error, LocalDateTime start) {
        SeedLoadRecord record = new SeedLoadRecord();
        record.setBatchId(batchId);
        record.setTenantId(tenantId);
        record.setTenantName(tenantName);
        record.setFileName(file.file());
        record.setChecksum(catalog.checksum(file));
        record.setBuildTime(catalog.buildTime());
        record.setStatus(status);
        record.setErrorMessage(error);
        record.setStartTime(start);
        record.setEndTime(LocalDateTime.now());
        ContextUtils.inSystemContext(() -> recordService.createOne(record));
    }

    /**
     * A file's records in one scope, as far as they decide its state.
     *
     * @param latest        the latest attempt
     * @param latestSuccess the latest successful one, or null
     */
    public record FileRecords(SeedLoadRecord latest, SeedLoadRecord latestSuccess) {
    }

    /** The platform's records, by file name. */
    public Map<String, FileRecords> platformRecords() {
        return byFile(ContextUtils.inSystemContext(() ->
                recordService.searchList(new Filters().isNotSet(SeedLoadRecord::getTenantId))));
    }

    /** These tenants' records, by tenant, then by file name. */
    public Map<Long, Map<String, FileRecords>> tenantRecords(Collection<Long> tenantIds) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        List<SeedLoadRecord> records = ContextUtils.inSystemContext(() ->
                recordService.searchList(new Filters().in(SeedLoadRecord::getTenantId, List.copyOf(tenantIds))));
        Map<Long, List<SeedLoadRecord>> byTenant = records.stream()
                .collect(Collectors.groupingBy(SeedLoadRecord::getTenantId));
        Map<Long, Map<String, FileRecords>> out = new HashMap<>();
        byTenant.forEach((tenantId, tenantRecords) -> out.put(tenantId, byFile(tenantRecords)));
        return out;
    }

    private static Map<String, FileRecords> byFile(List<SeedLoadRecord> records) {
        Map<String, FileRecords> out = new LinkedHashMap<>();
        records.stream()
                .sorted(Comparator.comparing(SeedLoadRecord::getId))
                .forEach(record -> {
                    FileRecords previous = out.get(record.getFileName());
                    SeedLoadRecord success = record.getStatus() == SeedRecordStatus.SUCCEEDED ? record
                            : previous == null ? null : previous.latestSuccess();
                    out.put(record.getFileName(), new FileRecords(record, success));
                });
        return out;
    }

    /**
     * Where a file stands in a scope: never loaded, loaded and unchanged, changed since — reloading or not,
     * as the file's level and flag say, unless a newer build than this one loaded it — or failed on its
     * latest attempt.
     *
     * @param records the file's records in the scope, or null when it has none
     */
    public FileState state(SeedFile file, FileRecords records) {
        if (records == null || records.latestSuccess() == null) {
            return records != null && records.latest().getStatus() == SeedRecordStatus.FAILED
                    ? FileState.FAILED : FileState.NOT_LOADED;
        }
        if (catalog.checksum(file).equals(records.latestSuccess().getChecksum())) {
            return FileState.LOADED;
        }
        if (!file.reloadsWhenChanged()) {
            return FileState.CHANGED_AFTER_LOAD;
        }
        if (builtAfterThisBuild(records.latestSuccess())) {
            return FileState.LOADED_BY_NEWER_BUILD;
        }
        return records.latest().getStatus() == SeedRecordStatus.FAILED ? FileState.FAILED : FileState.CHANGED;
    }

    /**
     * Whether the build that wrote the record was built after the running one — after a rollback, the file
     * here is the older content. Only known when both carry a build time; without one, a change is a change.
     */
    private boolean builtAfterThisBuild(SeedLoadRecord record) {
        LocalDateTime running = catalog.buildTime();
        return running != null && record.getBuildTime() != null && record.getBuildTime().isAfter(running);
    }
}
