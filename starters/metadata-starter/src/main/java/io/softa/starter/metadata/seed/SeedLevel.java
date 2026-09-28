package io.softa.starter.metadata.seed;

import lombok.Getter;

import io.softa.framework.base.constant.BaseConstant;

/**
 * Who a seed file's rows belong to, which decides where the file lives and how a change to it reaches
 * the data already loaded.
 */
@Getter
public enum SeedLevel {

    /**
     * Rows on shared (non-tenant) models — one copy for every tenant. A code change is meant to reach
     * everyone as soon as the platform re-applies the file.
     */
    PLATFORM_GLOBAL(BaseConstant.PREDEFINED_DATA_SYSTEM_DIR),

    /**
     * Rows on multi-tenant models, copied into every tenant when it is provisioned. Each tenant owns its
     * copy and may change it, so a later code change only adds what a tenant does not have yet.
     */
    TENANT(BaseConstant.PREDEFINED_DATA_TENANT_DIR),

    /**
     * Rows on multi-tenant models that belong to the platform's own tenant, not to any customer — loaded
     * once, under the platform tenant, and never copied into a provisioned tenant.
     */
    PLATFORM_TENANT(BaseConstant.PREDEFINED_DATA_PLATFORM_DIR);

    /** Classpath directory the level's files are read from, with a trailing slash. */
    private final String dataDir;

    SeedLevel(String dataDir) {
        this.dataDir = dataDir;
    }
}
