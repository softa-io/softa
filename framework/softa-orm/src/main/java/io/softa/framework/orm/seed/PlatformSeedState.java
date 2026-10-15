package io.softa.framework.orm.seed;

import java.util.Optional;

/**
 * Whether the platform-wide seed data is in step with the code that is running.
 *
 * <p>A tenant provisioned while it is not would be built on the previous release's shared rows —
 * permissions, plans, reference data — so tenant creation asks first. Implemented by the module that
 * applies the seeds; when no implementation is present, nothing is blocked.
 */
public interface PlatformSeedState {

    /**
     * @return why a tenant cannot be created now, or empty when it can
     */
    Optional<String> tenantCreationBlocker();
}
