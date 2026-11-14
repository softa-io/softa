package io.softa.starter.seed.manifest;

import java.util.Optional;

import io.softa.framework.base.constant.BaseConstant;

/**
 * Who a seed file's rows belong to, which decides the directory the file is read from and how a change to it
 * reaches the data already loaded.
 */
public enum SeedLevel {

    /** Rows on shared (non-tenant) models — one copy for everyone. Reloaded whole whenever the file changes. */
    PLATFORM_GLOBAL(BaseConstant.PREDEFINED_DATA_SYSTEM_DIR),

    /** Rows on multi-tenant models that belong to the platform's own tenant (-1). Reloaded whole like the above. */
    PLATFORM_TENANT(BaseConstant.PREDEFINED_DATA_PLATFORM_DIR),

    /**
     * Rows on multi-tenant models, run once in every tenant; the tenant owns its copy afterwards. A file in mode
     * {@code append} runs again when it changes, creating only the rows a tenant lacks; one in mode
     * {@code overwrite} runs again whole.
     */
    TENANT(BaseConstant.PREDEFINED_DATA_TENANT_DIR);

    /** Classpath directory of the level's files, with a trailing slash. */
    private final String dataDir;

    SeedLevel(String dataDir) {
        this.dataDir = dataDir;
    }

    public String dataDir() {
        return dataDir;
    }

    public boolean isPlatform() {
        return this != TENANT;
    }

    /** The level whose directory a classpath path sits in, e.g. {@code data-tenant/LeaveType.NZ.json}. */
    public static Optional<SeedLevel> ofPath(String path) {
        for (SeedLevel level : values()) {
            if (path.startsWith(level.dataDir)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
