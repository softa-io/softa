package io.softa.starter.metadata.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails the tenant tasks of a seed sync that have not finished in time, every few minutes. Without it a task
 * whose message was lost stays pending for good, and no later sync may start. Every instance runs it; a task
 * is failed once, whichever instance gets there first.
 */
@Slf4j
@Component
@EnableScheduling
public class SeedSyncTimeoutSweeper {

    private final SeedSyncService seedSyncService;

    public SeedSyncTimeoutSweeper(SeedSyncService seedSyncService) {
        this.seedSyncService = seedSyncService;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${softa.seed-sync.sweep-interval:PT5M}")
    public void sweep() {
        try {
            seedSyncService.failStaleTasks();
        } catch (RuntimeException e) {
            log.error("Could not fail the timed-out seed sync tasks", e);
        }
    }
}
