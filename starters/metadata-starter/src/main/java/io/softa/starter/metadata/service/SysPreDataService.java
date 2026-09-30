package io.softa.starter.metadata.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.seed.SeedPushScope;
import io.softa.starter.metadata.seed.TenantSeedFileResult;

/**
 * SysPreData Model Service Interface
 */
public interface SysPreDataService extends EntityService<SysPreData, Long> {

    /**
     * Load the specified list of predefined data files from the root directory: resources/data.
     * Supports data files in JSON, XML, and CSV formats. Data files support a two-layer domain model,
     * i.e., main model and subModel, but they will be created separately when loading.
     * The main model is created first to generate the main model id, then the subModel data is created.
     *
     * @param fileNames List of relative directory data file names to load
     */
    void loadPreSystemData(List<String> fileNames);

    /**
     * Load the specified list of predefined tenant data files from the root directory: resources/data-tenant.
     * Supports data files in JSON, XML, and CSV formats. Data files support a two-layer domain model,
     * i.e., main model and subModel, but they will be created separately when loading.
     * The main model is created first to generate the main model id, then the subModel data is created.
     *
     * <p>Setting a tenant up only — its provisioning, or rebuilding a setup that failed. Refused once the
     * tenant is set up ({@link #isSettingUp}), since a whole load overwrites what the tenant changed; a seed
     * sync brings such a tenant up to date instead.
     *
     * @param fileNames List of relative directory tenant data file names to load
     * @param tenantId tenant id to which the data will be loaded
     */
    void loadPreTenantData(List<String> fileNames, Long tenantId);

    /**
     * The marker a binding's source file takes when no current seed file declares its row: the row came
     * from a file or a row since removed, or was renamed. It says the binding was traced, and found nothing.
     */
    String UNTRACED_SOURCE = "(untraced)";

    /**
     * Stamp the source file on the bindings of one scope that do not record it — those written before
     * bindings recorded their file. A binding takes the file whose rows include its {@code Model/preId}, or
     * {@link #UNTRACED_SOURCE} when none does. Bindings that record a file are left as they are.
     *
     * @param fileOfRowKey the file declaring each {@code Model/preId}, for the scope's level; a preId a file
     *                     retired, keyed {@code * /preId} (no space) whatever its model
     * @param tenantId     the scope: a tenant, the platform tenant, or null for the shared rows
     * @return per file, how many bindings were traced to it; the untraced ones under
     *         {@link #UNTRACED_SOURCE}, with their keys as notes
     */
    List<TenantSeedFileResult> traceSources(Map<String, String> fileOfRowKey, Long tenantId);

    /**
     * Whether the tenant is still being set up — created and not built yet, or being built — so its seed
     * files may be loaded into it whole. True as well when the application keeps no tenant records, or none
     * for this tenant.
     *
     * @param tenantId tenant id
     * @return true while its setup has not finished
     */
    boolean isSettingUp(Long tenantId);

    /**
     * Load the specified list of predefined platform-tier data files from the root directory:
     * resources/data-platform. Rows land on the platform tier of {@code multiTenant} models —
     * {@code tenantId = BaseConstant.PLATFORM_TENANT_ID} (-1) — owned by the platform operator and
     * invisible to tenant-scoped reads. Same file formats and idempotency (SysPreData ledger keyed by
     * {@code (model, tenantId, preId)}) as the tenant loader.
     *
     * @param fileNames List of relative directory platform data file names to load
     */
    void loadPrePlatformData(List<String> fileNames);

    /**
     * Loads predefined data from a given multipart file.
     * <p>
     * This method processes the provided multipart file to load predefined data
     * into the system. The file is expected to be in a format that is recognized
     * by the implementation, such as CSV, JSON, or XML.
     * </p>
     * <p>
     * The method performs necessary validations and error handling to ensure the
     * data integrity and consistency. Any issues encountered during the file
     * processing are logged appropriately, and relevant exceptions are thrown to
     * inform the caller about the specific problems.
     * </p>
     *
     * @param file the multipart file containing the predefined data to be loaded
     *             into the system. The file should not be null and must contain
     *             valid data as per the required format.
     */
    void loadPreSystemData(MultipartFile file);

    /**
     * The rows a seed file declares, as {@code Model/preId} keys — its top-level rows and the rows nested
     * in them. What a version of the file is recorded as, so the next version's added and removed rows can
     * be told apart without keeping the file.
     *
     * @param dataDir  the level's directory, e.g. {@code data-tenant/}
     * @param fileName file name
     * @return the row keys, in file order
     */
    Set<String> rowKeysOf(String dataDir, String fileName);

    /**
     * How many rows loading these files whole creates: each row, and the rows nested in it at any depth.
     *
     * @param dataDir   the level's seed directory
     * @param fileNames the files, as the manifest names them
     */
    int rowCountOf(String dataDir, List<String> fileNames);

    /**
     * Bring the current tenant's copy of a tenant seed file up to date with one release's change to it,
     * without changing what the tenant already has: the rows the release added are created where the
     * tenant does not have them, and only the declared push reaches into rows it has. Rows the release did
     * not add are not visited at all — a gap the tenant had before is not filled. Never reloads the file.
     * Runs in the caller's tenant context and transaction.
     *
     * @param fileName file under data-tenant/
     * @param push     the change the manifest declares must still reach tenants that loaded the file
     * @param added    row keys ({@link #rowKeysOf}) the release added to the file
     * @param removed  row keys the release removed from it
     * @return what it did in the tenant
     */
    TenantSeedFileResult applyNewRows(String fileName, SeedPushScope push, Set<String> added, Set<String> removed);
}
