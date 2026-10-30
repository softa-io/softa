package io.softa.starter.seed.service;

import java.util.List;
import java.util.Map;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.seed.entity.SysPreData;

/**
 * SysPreData Model Service Interface: the preId ledger and the loader that writes seed files through it.
 *
 * <p>The loader only loads. Which files run, when, and in which scope is decided by the seed data operations
 * ({@code PlatformSeedLoader}, {@code TenantSeedRunner}), which also record every load.
 */
public interface SysPreDataService extends EntityService<SysPreData, Long> {

    /**
     * Load platform-global seed files from {@code resources/data-system}, at system scope (tenantId = null),
     * in the caller's transaction. JSON and CSV are supported; a row may carry the rows of its OneToMany
     * fields, which are created after it.
     *
     * @param fileNames file names relative to the directory, loaded in this order
     * @param mode      how rows the scope already has are treated
     */
    void loadPreSystemData(List<String> fileNames, LoadMode mode);

    /**
     * Load tenant seed files from {@code resources/data-tenant} into one tenant, in the caller's transaction.
     *
     * @param fileNames file names relative to the directory, loaded in this order
     * @param tenantId  the tenant the rows are written to
     * @param mode      how rows the tenant already has are treated
     * @return the rows the load created, and the rows an earlier load had created that this one changed
     */
    LoadedRows loadPreTenantData(List<String> fileNames, Long tenantId, LoadMode mode);

    /**
     * Load the platform tenant's seed files from {@code resources/data-platform}. Rows land on the platform
     * tier of {@code multiTenant} models — {@code tenantId = BaseConstant.PLATFORM_TENANT_ID} (-1) — owned by
     * the platform operator and invisible to tenant-scoped reads.
     *
     * @param fileNames file names relative to the directory, loaded in this order
     * @param mode      how rows already there are treated
     */
    void loadPrePlatformData(List<String> fileNames, LoadMode mode);

    /**
     * The preIds of a file's top-level rows, by model — what a scope's bindings are compared against to tell
     * whether the file was loaded into it before.
     *
     * @param dataDir  the level's directory, e.g. {@code data-tenant/}
     * @param fileName file name
     * @return model name → the preIds of its top-level rows in the file
     */
    Map<String, List<String>> filePreIds(String dataDir, String fileName);
}
