package io.softa.starter.studio.release.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import io.softa.starter.studio.release.service.DesignAppEnvService;

/**
 * Runs {@code refreshDrift} on a virtual-thread {@code @Async} pool so callers
 * (controllers, upstream listeners) do not block on the parallel metadata-export
 * fan-out. Any failure is captured on the {@code DesignAppEnvDrift} row by
 * {@code refreshDrift} itself; this listener only guards against that method
 * throwing past its own error handling.
 */
@Slf4j
@Component
public class DesignAppEnvDriftRefreshListener {

    private final DesignAppEnvService appEnvService;

    public DesignAppEnvDriftRefreshListener(DesignAppEnvService appEnvService) {
        this.appEnvService = appEnvService;
    }

    @Async
    @EventListener
    public void onDriftRefresh(DesignAppEnvDriftRefreshEvent event) {
        try {
            appEnvService.refreshDrift(event.envId());
        } catch (RuntimeException e) {
            log.warn("Drift refresh failed for env {}: {}", event.envId(), e.getMessage());
        }
    }
}
