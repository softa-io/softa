package io.softa.starter.tenant.provisioning;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.seed.PlatformSeedState;

/**
 * Refuses to build a tenant on platform seed data that is behind the running code: the tenant would get
 * the previous release's permissions, plans and reference rows, and nothing would later tell it apart.
 * An application with no {@link PlatformSeedState} is never blocked.
 */
@Component
public class PlatformSeedGuard {

    private final ObjectProvider<PlatformSeedState> seedState;

    public PlatformSeedGuard(ObjectProvider<PlatformSeedState> seedState) {
        this.seedState = seedState;
    }

    public void requireInStep() {
        PlatformSeedState state = seedState.getIfAvailable();
        if (state == null) {
            return;
        }
        state.tenantCreationBlocker().ifPresent(reason -> {
            throw new BusinessException(reason);
        });
    }
}
