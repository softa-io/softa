package io.softa.starter.permission.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.broadcast.ClusterBroadcastListener;
import io.softa.framework.orm.broadcast.ClusterEvents;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;

/**
 * Rebuilds the permission engine's in-memory indexes when the platform seeds are re-applied, on every
 * instance, so a permission or sensitive field set added by a release takes effect without a restart.
 *
 * <p>Both rebuilds keep the previous index when they fail, so a failure here leaves the instance
 * enforcing what it enforced before, never nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformSeedSyncedListener implements ClusterBroadcastListener {

    private final EndpointIndex endpointIndex;
    private final SensitiveFieldSetCache sensitiveFieldSetCache;

    @Override
    public String event() {
        return ClusterEvents.PLATFORM_SEED_SYNCED;
    }

    @Override
    public void onEvent() {
        // The listener thread has no request context; read the shared rows as the startup build does.
        ContextUtils.inSystemContext(() -> {
            endpointIndex.reload();
            sensitiveFieldSetCache.reload();
        });
        log.info("Permission indexes rebuilt after the platform seeds were re-applied");
    }
}
