package io.softa.starter.metadata.seed;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.entity.SeedSyncBatch;
import io.softa.starter.metadata.entity.SeedSyncTask;
import io.softa.starter.metadata.enums.SeedSyncStatus;
import io.softa.starter.metadata.enums.SeedSyncTaskStatus;
import io.softa.starter.metadata.service.SeedSyncBatchService;
import io.softa.starter.metadata.service.SeedSyncTaskService;
import io.softa.starter.metadata.service.SysPreDataService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A tenant task that has not finished in time is failed — waiting for a message that never came, or running
 * where nobody finishes it — so it can be retried and no longer holds every later sync back. A task still
 * within its time is left alone.
 */
class SeedSyncStaleTaskTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final Long BATCH = 100L;

    private SeedSyncTaskService taskService;
    private SeedSyncBatchService batchService;
    private TenantSeedSyncRunner runner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskService = mock(SeedSyncTaskService.class);
        batchService = mock(SeedSyncBatchService.class);
        runner = new TenantSeedSyncRunner(mock(ObjectProvider.class), mock(SysPreDataService.class), batchService,
                taskService, mock(ModelService.class), mock(CacheService.class), mock(TenantSeedScope.class),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void tasksPastTheirTimeAreFailedAndTheirBatchClosed() {
        SeedSyncTask lost = task(1L, SeedSyncTaskStatus.PENDING, null, LocalDateTime.now().minusMinutes(31));
        SeedSyncTask stuck = task(2L, SeedSyncTaskStatus.RUNNING, LocalDateTime.now().minusMinutes(45),
                LocalDateTime.now().minusMinutes(46));
        SeedSyncTask recent = task(3L, SeedSyncTaskStatus.RUNNING, LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().minusMinutes(40));
        // First read: the unfinished tasks. Then the batch recount: all of them, the two now failed.
        SeedSyncTask lostFailed = task(1L, SeedSyncTaskStatus.FAILED, null, null);
        SeedSyncTask stuckFailed = task(2L, SeedSyncTaskStatus.FAILED, null, null);
        when(taskService.searchList(any(Filters.class)))
                .thenReturn(List.of(lost, stuck, recent))
                .thenReturn(List.of(lostFailed, stuckFailed, recent));

        int failed = runner.failStaleTasks(TIMEOUT);

        assertThat(failed).isEqualTo(2);
        ArgumentCaptor<SeedSyncTask> patches = ArgumentCaptor.forClass(SeedSyncTask.class);
        verify(taskService, atLeastOnce()).updateOne(patches.capture());
        assertThat(patches.getAllValues()).extracting(SeedSyncTask::getId).containsExactly(1L, 2L);
        assertThat(patches.getAllValues()).allSatisfy(patch -> {
            assertThat(patch.getStatus()).isEqualTo(SeedSyncTaskStatus.FAILED);
            assertThat(patch.getErrorSummary()).startsWith("Timed out after 30 minutes");
        });
        assertThat(patches.getAllValues().get(0).getErrorSummary()).contains("never started");
        assertThat(patches.getAllValues().get(1).getErrorSummary()).contains("still running");

        // The batch still has a task running, so it is recounted but stays open.
        ArgumentCaptor<SeedSyncBatch> batch = ArgumentCaptor.forClass(SeedSyncBatch.class);
        verify(batchService).updateOne(batch.capture());
        assertThat(batch.getValue().getFailedTenants()).isEqualTo(2);
        assertThat(batch.getValue().getStatus()).isNotEqualTo(SeedSyncStatus.SUCCEEDED);
    }

    @Test
    void nothingPastItsTimeChangesNothing() {
        when(taskService.searchList(any(Filters.class))).thenReturn(List.of(
                task(3L, SeedSyncTaskStatus.PENDING, null, LocalDateTime.now().minusMinutes(2))));

        assertThat(runner.failStaleTasks(TIMEOUT)).isZero();
        verify(taskService, never()).updateOne(any(SeedSyncTask.class));
    }

    private static SeedSyncTask task(Long id, SeedSyncTaskStatus status, LocalDateTime started, LocalDateTime created) {
        SeedSyncTask task = new SeedSyncTask();
        task.setId(id);
        task.setBatchId(BATCH);
        task.setTenantId(7L);
        task.setStatus(status);
        task.setStartTime(started);
        task.setCreatedTime(created);
        return task;
    }
}
