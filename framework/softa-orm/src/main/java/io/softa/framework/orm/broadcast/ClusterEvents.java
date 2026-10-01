package io.softa.framework.orm.broadcast;

/**
 * Names of the events published through {@link ClusterBroadcaster}, shared by the module that
 * publishes each one and the modules that listen for it.
 */
public final class ClusterEvents {

    /**
     * The platform-wide seed files were re-applied: permission, navigation and other shared rows may
     * have changed, so every instance rebuilds what it derived from them at startup.
     */
    public static final String PLATFORM_SEED_SYNCED = "platform-seed-synced";

    private ClusterEvents() {
    }
}
