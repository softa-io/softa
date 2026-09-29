package io.softa.starter.metadata.seed;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.entity.SeedSyncBatch;
import io.softa.starter.metadata.entity.SeedSyncTask;
import io.softa.starter.metadata.enums.SeedSyncStatus;
import io.softa.starter.metadata.enums.SeedSyncTaskStatus;
import io.softa.starter.metadata.service.SeedSyncBatchService;
import io.softa.starter.metadata.service.SeedSyncTaskService;
import io.softa.starter.metadata.service.SysPreDataService;

import static io.softa.starter.metadata.seed.SyncSupport.asSystem;
import static io.softa.starter.metadata.seed.SyncSupport.messageOf;
import static io.softa.starter.metadata.seed.SyncSupport.truncate;

/**
 * Brings one tenant up to date for a batch — its {@link SeedSyncTask} — and closes the batch once its last
 * tenant is done.
 *
 * <p>All the batch's tenant files are applied in the tenant in one transaction: a failure anywhere rolls the
 * tenant back to where it was, records why, and leaves every other tenant alone. Nothing the tenant already
 * has is changed except by a push the manifest declares ({@link SysPreDataService#applyNewRows}).
 */
@Slf4j
@Service
public class TenantSeedSyncRunner {

    static final String TENANT_MODEL = "TenantInfo";
    /** Tenants a sync brings up to date. A tenant still being set up loads the current files itself. */
    static final List<String> SYNCED_TENANT_STATUSES = List.of("Active", "Suspended");

    private final ObjectProvider<SeedManifest> manifestProvider;
    private final SysPreDataService preDataService;
    private final SeedSyncBatchService batchService;
    private final SeedSyncTaskService taskService;
    private final ModelService<?> modelService;
    private final CacheService cacheService;
    private final TransactionTemplate transaction;

    public TenantSeedSyncRunner(ObjectProvider<SeedManifest> manifestProvider,
                                SysPreDataService preDataService,
                                SeedSyncBatchService batchService,
                                SeedSyncTaskService taskService,
                                ModelService<?> modelService,
                                CacheService cacheService,
                                PlatformTransactionManager transactionManager) {
        this.manifestProvider = manifestProvider;
        this.preDataService = preDataService;
        this.batchService = batchService;
        this.taskService = taskService;
        this.modelService = modelService;
        this.cacheService = cacheService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Run one task. A task already finished is left as it is, so a redelivered message does nothing.
     */
    public void runTask(Long taskId) {
        SeedSyncTask task = asSystem(() -> taskService.getById(taskId).orElse(null));
        if (task == null || !isUnfinished(task.getStatus())) {
            return;
        }
        SeedSyncBatch batch = asSystem(() -> batchService.getById(task.getBatchId()).orElse(null));
        if (batch == null) {
            return;
        }
        Long tenantId = task.getTenantId();
        asSystem(() -> updateTask(taskId, t -> {
            t.setStatus(SeedSyncTaskStatus.RUNNING);
            t.setAttempt((task.getAttempt() == null ? 0 : task.getAttempt()) + 1);
            t.setStartTime(LocalDateTime.now());
        }));
        if (!isSyncedTenant(tenantId)) {
            asSystem(() -> updateTask(taskId, t -> {
                t.setStatus(SeedSyncTaskStatus.SKIPPED);
                t.setEndTime(LocalDateTime.now());
                t.setErrorSummary("The tenant is no longer active or suspended.");
            }));
            finishBatchIfDone(batch.getId());
            return;
        }
        List<TenantFileChange> changes = changesOf(batch);
        String country = countryOf(tenantId);
        List<TenantSeedFileResult> results = new ArrayList<>();
        String[] current = new String[1];
        try {
            asInitiator(batch, () -> ContextUtils.inTenantContext(tenantId, () -> transaction.executeWithoutResult(
                    status -> {
                        results.clear();
                        for (TenantFileChange change : changes) {
                            current[0] = change.file();
                            SeedFile file = manifestProvider.getObject().file(change.file()).orElse(null);
                            if (file == null || !file.appliesToCountry(country)) {
                                continue;
                            }
                            results.add(preDataService.applyNewRows(file.file(), file.push(),
                                    Set.copyOf(change.added()), Set.copyOf(change.removed())));
                        }
                    })));
        } catch (RuntimeException e) {
            log.error("Seed sync batch {} failed for tenant {} on {}", batch.getId(), tenantId, current[0], e);
            String error = current[0] + ": " + messageOf(e);
            asSystem(() -> updateTask(taskId, t -> {
                t.setStatus(SeedSyncTaskStatus.FAILED);
                t.setEndTime(LocalDateTime.now());
                t.setErrorSummary(truncate(error + " — everything this sync wrote in the tenant was rolled back."));
            }));
            finishBatchIfDone(batch.getId());
            return;
        }
        int newRows = results.stream().mapToInt(r -> r.created() + r.claimed()).sum();
        int pushed = results.stream().mapToInt(r -> r.pushed() + r.removed()).sum();
        asSystem(() -> updateTask(taskId, t -> {
            t.setStatus(SeedSyncTaskStatus.SUCCEEDED);
            t.setEndTime(LocalDateTime.now());
            t.setNewRowCount(newRows);
            t.setPushedCount(pushed);
            t.setFileResults(JsonUtils.objectToString(results));
        }));
        if (newRows + pushed > 0) {
            // A new role or grant changes what this tenant's users may do; their cached snapshots go.
            cacheService.clearByPrefix("perm:" + tenantId + ":");
        }
        log.info("Seed sync batch {} brought tenant {} up to date: {} new row(s), {} pushed", batch.getId(),
                tenantId, newRows, pushed);
        finishBatchIfDone(batch.getId());
    }

    /**
     * Refresh a batch's tenant counts and, once no task is left to run, close it: Succeeded when every
     * tenant made it, FinishedWithFailures otherwise. Safe to call from any number of tasks at once — each
     * call recounts from the tasks.
     */
    public void finishBatchIfDone(Long batchId) {
        asSystem(() -> {
            List<SeedSyncTask> tasks = taskService.searchList(new Filters().eq(SeedSyncTask::getBatchId, batchId));
            int succeeded = count(tasks, SeedSyncTaskStatus.SUCCEEDED);
            int failed = count(tasks, SeedSyncTaskStatus.FAILED);
            int skipped = count(tasks, SeedSyncTaskStatus.SKIPPED);
            boolean done = tasks.stream().noneMatch(t -> isUnfinished(t.getStatus()));
            SeedSyncBatch patch = new SeedSyncBatch();
            patch.setId(batchId);
            patch.setTotalTenants(tasks.size());
            patch.setSucceededTenants(succeeded);
            patch.setFailedTenants(failed);
            patch.setSkippedTenants(skipped);
            if (done) {
                patch.setStatus(failed > 0 ? SeedSyncStatus.FINISHED_WITH_FAILURES : SeedSyncStatus.SUCCEEDED);
                patch.setEndTime(LocalDateTime.now());
            }
            batchService.updateOne(patch);
        });
    }

    /** Whether the batch still has tenants waiting or running. */
    public boolean hasUnfinishedTasks(Long batchId) {
        return asSystem(() -> taskService.exist(new Filters().eq(SeedSyncTask::getBatchId, batchId)
                .in(SeedSyncTask::getStatus, List.of(SeedSyncTaskStatus.PENDING, SeedSyncTaskStatus.RUNNING))));
    }

    /** Ids of the tenants a sync brings up to date. */
    public List<Long> syncedTenantIds() {
        if (!ModelManager.existModel(TENANT_MODEL)) {
            return List.of();
        }
        return asSystem(() -> modelService.searchList(TENANT_MODEL,
                        new FlexQuery(List.of("id"), new Filters().in("status", SYNCED_TENANT_STATUSES)))
                .stream().map(row -> Long.valueOf(String.valueOf(row.get("id")))).toList());
    }

    private boolean isSyncedTenant(Long tenantId) {
        if (!ModelManager.existModel(TENANT_MODEL)) {
            return false;
        }
        return asSystem(() -> modelService.count(TENANT_MODEL,
                new Filters().eq("id", tenantId).in("status", SYNCED_TENANT_STATUSES)) > 0);
    }

    /**
     * The batch's changes to tenant files, in manifest load order, so a file's new rows are created after
     * the rows of the files they depend on.
     */
    private List<TenantFileChange> changesOf(SeedSyncBatch batch) {
        if (StringUtils.isBlank(batch.getTenantChanges())) {
            return List.of();
        }
        List<TenantFileChange> changes = JsonUtils.stringToObject(batch.getTenantChanges(),
                new TypeReference<List<TenantFileChange>>() {});
        List<String> order = manifestProvider.getObject().loadOrder(SeedLevel.TENANT);
        return changes.stream().filter(TenantFileChange::reachesTenants)
                .sorted(Comparator.comparingInt(change -> order.indexOf(change.file())))
                .toList();
    }

    /** The tenant's country — its default country, which is what a tenant's files are chosen by. */
    String countryOf(Long tenantId) {
        if (!ModelManager.existModel(TENANT_MODEL)) {
            return null;
        }
        return asSystem(() -> modelService.searchList(TENANT_MODEL,
                        new FlexQuery(List.of("id", "defaultCountry"), new Filters().eq("id", tenantId)))
                .stream().findFirst().map(row -> codeOf(row.get("defaultCountry"))).orElse(null));
    }

    private static String codeOf(Object value) {
        if (value instanceof Map<?, ?> reference) {
            Object id = reference.get("id");
            return id == null ? null : String.valueOf(id);
        }
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Run as the person who started the batch, so what the task writes is attributed to them rather than
     * to nobody — a consumer thread has no request behind it.
     */
    private static void asInitiator(SeedSyncBatch batch, Runnable action) {
        Context context = ContextHolder.cloneContext();
        if (context.getUserId() == null) {
            context.setUserId(batch.getCreatedId());
            context.setName(batch.getCreatedBy());
        }
        ContextHolder.runWith(context, action);
    }

    private void updateTask(Long taskId, Consumer<SeedSyncTask> change) {
        SeedSyncTask patch = new SeedSyncTask();
        patch.setId(taskId);
        change.accept(patch);
        taskService.updateOne(patch);
    }

    static boolean isUnfinished(SeedSyncTaskStatus status) {
        return status == SeedSyncTaskStatus.PENDING || status == SeedSyncTaskStatus.RUNNING;
    }

    private static int count(List<SeedSyncTask> tasks, SeedSyncTaskStatus status) {
        return (int) tasks.stream().filter(t -> t.getStatus() == status).count();
    }
}
