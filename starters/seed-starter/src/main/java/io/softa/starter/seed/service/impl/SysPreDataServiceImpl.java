package io.softa.starter.seed.service.impl;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.domain.FileObject;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.FileUtils;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.seed.entity.SysPreData;
import io.softa.starter.seed.service.LoadMode;
import io.softa.starter.seed.service.LoadedRows;
import io.softa.starter.seed.service.SysPreDataService;

import static io.softa.framework.orm.constant.ModelConstant.*;

/**
 * SysPreData Model Service Implementation
 * Predefined data: model + preId as a unique identifier within a loading scope (system, or one
 * tenant), used to bind model row ID. ManyToOne and OneToOne fields directly reference preId,
 * ManyToMany fields reference a list of preIds, OneToMany fields support a data list, where the
 * data in the list does not need to declare the main model's preId but must declare the
 * relatedModel's preId.
 * <p>
 * Scope follows the model, not the file: a binding lives in the scope of the model it binds
 * ({@link #bindingScopeOf}), and a reference resolves in the scope of the model it points AT
 * ({@link #referenceScopeOf}), so references cross the tenancy boundary in either direction. What
 * each file may WRITE is still bounded by its own tenancy ({@link #validateSeedScope}). Cross-scope
 * references make load order load-bearing: system seeds before the tenant seeds referencing them.
 * <p>
 * File-format concerns (JSON / CSV / XML) are delegated to {@link PreDataFormatParser}; this service owns the
 * predefined-data domain logic only — preId binding, main/sub-model ordering, and create-or-update reconciliation.
 * <p>
 * Every load runs in a {@link LoadMode}, bound for the length of the load and read wherever a row the scope
 * already has is met: {@code OVERWRITE} rewrites it from the file, {@code ADDITIVE} writes only what the file
 * names and leaves deletions standing, {@code APPEND} writes nothing to it and creates only the rows the scope
 * lacks.
 */
@Slf4j
@Service
public class SysPreDataServiceImpl extends EntityServiceImpl<SysPreData, Long> implements SysPreDataService {

    /** The mode of the load in progress. Bound around one load; unbound outside one. */
    private static final ScopedValue<LoadMode> MODE = ScopedValue.newInstance();

    /** The rows the load in progress created, by model; bound only around a load that reports them. */
    private static final ScopedValue<Map<String, List<String>>> CREATED = ScopedValue.newInstance();

    /** The rows of earlier loads the load in progress wrote to, by model; bound together with {@link #CREATED}. */
    private static final ScopedValue<Map<String, List<String>>> UPDATED = ScopedValue.newInstance();

    private final ModelService<Serializable> modelService;
    private final PreDataFormatParser formatParser = new PreDataFormatParser();

    public SysPreDataServiceImpl(ModelService<Serializable> modelService) {
        this.modelService = modelService;
    }

    /**
     * Load platform-global seed files from {@code resources/data-system} at system scope. Supports JSON, XML
     * and CSV. A row may carry the rows of its OneToMany fields: the main row is created first, so its id can
     * back-reference into the children, then the children.
     *
     * @param fileNames file names relative to the directory
     * @param mode      how rows the scope already has are treated
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void loadPreSystemData(List<String> fileNames, LoadMode mode) {
        runAsSystemScope(() -> loadFiles(BaseConstant.PREDEFINED_DATA_SYSTEM_DIR, fileNames, mode));
    }

    /**
     * Load tenant seed files from {@code resources/data-tenant} into one tenant. Same formats and nesting as
     * {@link #loadPreSystemData}.
     *
     * @param fileNames file names relative to the directory
     * @param tenantId  tenant the rows are written to
     * @param mode      how rows the tenant already has are treated
     * @return the rows the load created, and the rows of earlier loads it wrote to, by model
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoadedRows loadPreTenantData(List<String> fileNames, Long tenantId, LoadMode mode) {
        if (SystemConfig.env.isEnableMultiTenancy()) {
            Assert.notNull(tenantId,
                    "Loading tenant predefined data requires a tenant id when multi-tenancy is enabled!");
        }
        return reportingWrites(() -> loadInTenantScope(BaseConstant.PREDEFINED_DATA_TENANT_DIR, fileNames, tenantId, mode));
    }

    /**
     * Run a load, noting the rows it creates and the rows of earlier loads it writes to. Package-private for the
     * tests that drive one record.
     */
    static LoadedRows reportingWrites(Runnable load) {
        Map<String, List<String>> created = new LinkedHashMap<>();
        Map<String, List<String>> updated = new LinkedHashMap<>();
        ScopedValue.where(CREATED, created).where(UPDATED, updated).run(load);
        return new LoadedRows(created, updated);
    }

    /** Note a row the load created, when the load reports them (see {@link #loadPreTenantData}). */
    private static void recordCreated(String model, Serializable rowId) {
        note(CREATED, model, rowId);
    }

    /** Note a row of an earlier load that the load wrote to, when the load reports them. */
    private static void recordUpdated(String model, Serializable rowId) {
        note(UPDATED, model, rowId);
    }

    private static void note(ScopedValue<Map<String, List<String>>> rows, String model, Serializable rowId) {
        if (rows.isBound()) {
            rows.get().computeIfAbsent(model, k -> new ArrayList<>()).add(String.valueOf(rowId));
        }
    }

    /**
     * Platform-tier load: the tenant loader pointed at {@code data-platform/} with the reserved
     * platform tenant id (-1), so seeded rows are platform-owned and invisible to tenant reads.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void loadPrePlatformData(List<String> fileNames, LoadMode mode) {
        loadInTenantScope(BaseConstant.PREDEFINED_DATA_PLATFORM_DIR, fileNames,
                BaseConstant.PLATFORM_TENANT_ID, mode);
    }

    @Override
    public Map<String, List<String>> filePreIds(String dataDir, String fileName) {
        Map<String, List<String>> preIds = new LinkedHashMap<>();
        formatParser.parse(FileUtils.getFileObjectByPath(dataDir, fileName)).forEach((model, data) -> {
            List<Object> rows = data instanceof Map<?, ?> single ? List.of(single) : Cast.of(data);
            List<String> modelPreIds = preIds.computeIfAbsent(model, k -> new ArrayList<>());
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map && map.get(ID) instanceof String preId) {
                    modelPreIds.add(preId);
                }
            }
        });
        return preIds;
    }

    /** Load each file in turn, with the mode bound for the whole of it. */
    private void loadFiles(String dataDir, List<String> fileNames, LoadMode mode) {
        Assert.notNull(mode, "A load mode is required.");
        ScopedValue.where(MODE, mode).run(() -> {
            for (String fileName : fileNames) {
                loadFileObject(FileUtils.getFileObjectByPath(dataDir, fileName));
            }
        });
    }

    /**
     * Whether the load in progress overwrites. A record handed straight to {@link #handlePredefinedData} — a
     * unit test — does, as every load did before modes existed. The other modes clear nothing, delete nothing,
     * and leave what the scope deleted deleted.
     */
    private static boolean overwriting() {
        return !MODE.isBound() || MODE.get() == LoadMode.OVERWRITE;
    }

    /** Whether the load in progress only creates the rows the scope lacks, writing nothing to the ones it has. */
    private static boolean appending() {
        return MODE.isBound() && MODE.get() == LoadMode.APPEND;
    }

    /** Run with a load mode bound, as a file load does. Package-private for the tests that drive one record. */
    static <T> T inMode(LoadMode mode, java.util.function.Supplier<T> action) {
        return ScopedValue.where(MODE, mode).call(action::get);
    }

    private void loadInTenantScope(String dataDir, List<String> fileNames, Long tenantId, LoadMode mode) {
        Context tenantContext = ContextHolder.cloneContext();
        tenantContext.setTenantId(tenantId);
        // Set here as well, even though this path currently works. It works only because the caller is
        // usually an admin *of the tenant being loaded*, so the snapshot resolves. Load another tenant's
        // data — which is exactly what provisioning does — and the caller is not in that tenant's
        // user_role_rel, the snapshot comes back empty, and it fails the same way the system path does.
        // A latent version of the same bug rather than a different one.
        //
        // ContextUtils.inTenantContext expresses the same three settings, but on a `new Context()` — the
        // seeded rows would lose their createdId / createdBy. See runAsSystemScope for the full reason.
        tenantContext.setSkipPermissionCheck(true);
        // Cloned from the caller, so a caller running cross-tenant (inSystemContext, a background job) would
        // hand that on: reads would lose the tenant predicate while the bindings name this tenant. The load
        // is this tenant's, whatever its caller.
        tenantContext.setCrossTenant(false);
        ContextHolder.runWith(tenantContext, () -> loadFiles(dataDir, fileNames, mode));
    }

    /**
     * Run a load at system scope: bindings and seeded rows take tenantId = null regardless
     * of the caller's ambient tenant, so a tenant-context caller cannot tenant-stamp system seeds.
     */
    private void runAsSystemScope(Runnable task) {
        Context systemContext = ContextHolder.cloneContext();
        systemContext.setTenantId(null);
        // Row-scope must be skipped too, or a system-scope load cannot read its own bindings.
        //
        // The proof is the asymmetry with loadPreTenantData: the two differ in this one assignment, and
        // tenant loads work while system loads fail. The permission snapshot is keyed by
        // (tenantId, userId), so a real tenant id resolves it, the caller comes back an admin, and
        // appendScopeAccessFilters short-circuits. A null tenant id queries the multi-tenant role tables
        // for a tenant that does not exist, so the snapshot is empty, empty is not an admin,
        // `SysPreData` has a forward scope anchor (`createdId`, so CREATED_BY_SELF applies), and a model
        // with an anchor and no explicit grant is fail-closed to matchNone(). The binding lookup then
        // returns nothing and the load dies on its first referenced row with "the preIDs … do not
        // exist" — naming data that is in fact present, which is why this reads as a data problem.
        //
        // Loading predefined data is an internal operation running under the caller's already-verified
        // authority. That is what skipPermissionCheck is for.
        //
        // Same intent as ContextUtils.inSystemContext, deliberately not that method. Two reasons, both
        // load-bearing: it builds a `new Context()` where this clones, so the caller's userId would be
        // gone and every seeded row plus every binding would carry null createdId / createdBy — the
        // record of who loaded it; and it expresses "system" as crossTenant = true, which waives the
        // tenant predicate on every read in the window, where `tenantId = null` says exactly the one
        // thing needed (write tenant_id = null, read IS NULL). Its own javadoc scopes it to background
        // orchestration rather than request-scoped work, and this is a REST call.
        systemContext.setSkipPermissionCheck(true);
        // And not inherited from the caller either, for the same reason as the tenant path.
        systemContext.setCrossTenant(false);
        ContextHolder.runWith(systemContext, task);
    }

    /**
     * Parse a file into its {@code modelName -> data} entries (format handling lives in
     * {@link PreDataFormatParser}) and load each model's predefined data in declaration order.
     *
     * @param fileObject fileObject with the file content
     */
    private void loadFileObject(FileObject fileObject) {
        formatParser.parse(fileObject).forEach(this::processModelData);
    }

    /**
     * Process the model predefined data, which can be a single Map or a List<Map> format.
     *
     * @param model Model name
     * @param predefinedData Predefined data
     */
    private void processModelData(String model, Object predefinedData) {
        ModelManager.validateModel(model);
        if (ModelManager.isTimelineModel(model)) {
            List<?> rows = predefinedData instanceof List<?> listData ? listData : List.of(predefinedData);
            checkTimelineRows(model, rows.stream()
                    .filter(Map.class::isInstance)
                    .map(row -> Cast.<Map<String, Object>>of(row))
                    .toList());
        }
        if (predefinedData instanceof List<?> listData) {
            listData.forEach(row -> {
                if (row instanceof Map<?, ?> rowMap) {
                    handlePredefinedData(model, Cast.of(rowMap));
                } else {
                    throw new IllegalArgumentException("When defining model data in List structure, " +
                            "the internal data only supports Map format {0}: {1}", model, predefinedData);
                }
            });
        } else if (predefinedData instanceof Map<?, ?> mapData) {
            handlePredefinedData(model, Cast.of(mapData));
        } else {
            throw new IllegalArgumentException(
                    "Model predefined data only supports Map or List<Map> format {0}: {1}", model, predefinedData);
        }
    }

    /**
     * Load a predefined data record.
     * If there is predefined data for OneToMany fields, recursively load the sub-table data after the
     * main row exists (so the generated main id can back-reference into it). The input {@code row} is
     * treated as read-only — it is split into a main-model map and a OneToMany map.
     * When the OneToMany field value is empty, it indicates the deletion of existing associated model data.
     *
     * <p>Package-private so one seed record — the main row, its frozen state, and the reconciliation
     * of the children it owns — can be driven from a unit test without a whole file load, the same
     * reason {@link #bindingScopeOf} is.
     *
     * @param model Model name
     * @param row Predefined data record
     * @return the row id, or null when an additive or append load left the record out — the scope deleted the
     *         row, or the row refers to one the scope deleted — together with the children it carries
     */
    Serializable handlePredefinedData(String model, Map<String, Object> row) {
        validateSeedScope(model);
        Map<String, Object> mainRow = new LinkedHashMap<>();
        Map<String, Object> oneToManyMap = new LinkedHashMap<>();
        // Separate OneToMany sub-data from the main-model fields; an ordered map keeps the
        // processing order consistent with the file definition.
        row.forEach((field, value) -> {
            if (FieldType.ONE_TO_MANY.equals(ModelManager.getModelField(model, field).getFieldType())) {
                oneToManyMap.put(field, value);
            } else {
                mainRow.put(field, value);
            }
        });
        // Frozen is decided here rather than inside createOrUpdateData, so that it covers the whole
        // record — the main row AND the children it owns. Freezing only the main row protected the
        // half a re-load barely touches while leaving the destructive half unguarded: child sync
        // deletes every row the file no longer declares, including the grants and option items an
        // operator added by hand. That is what a freeze is for.
        Optional<SysPreData> binding = getPreDataByPreId(model, mainRow);
        if (binding.isPresent() && Boolean.TRUE.equals(binding.get().getFrozen())) {
            return IdUtils.formatId(model, binding.get().getRowId());
        }
        // Load main model data first, then the OneToMany rows it owns.
        Serializable rowId = createOrUpdateData(model, mainRow, binding);
        if (rowId == null) {
            // Left out by an additive or append load; the children it carries have nothing to hang off.
            return null;
        }
        loadOneToManyRows(model, rowId, oneToManyMap);
        return rowId;
    }

    /**
     * A seed row must match the scope it is loaded under when multi-tenancy is enabled:
     * a system-scope load writing a multi-tenant model would stamp rows with tenantId = null
     * that no tenant can read, and a tenant-scope load writing a shared model would duplicate
     * the shared rows once per loading tenant. Both directions fail fast; the surrounding
     * transaction rolls the whole file back. Checked here so OneToMany sub-model recursion
     * is covered, not just top-level entries.
     *
     * @param model Model name being seeded
     */
    private void validateSeedScope(String model) {
        if (!SystemConfig.env.isEnableMultiTenancy()) {
            return;
        }
        boolean tenantScope = ContextHolder.getContext().getTenantId() != null;
        boolean tenantModel = ModelManager.getModel(model).isMultiTenant();
        Assert.notTrue(!tenantScope && tenantModel,
                "Model {0} is multi-tenant: its seed rows belong in a tenant file (data-tenant) or a platform " +
                "tenant file (data-platform), not in a platform-global file.", model);
        Assert.notTrue(tenantScope && !tenantModel,
                "Model {0} is a shared model: its seed rows belong in a platform-global file (data-system), " +
                "not in a tenant file.", model);
    }

    /**
     * Reconcile a main row's OneToMany children with the file: the children it declares are created or
     * updated; on an overwriting load, the seeded children it no longer declares are deleted first.
     *
     * <p>Reconciliation runs BEFORE the writes, and what survives is read from the bindings — the rows
     * the file's child preIds point at — rather than from what the writes just returned. The order is
     * the whole point. Writing first and deleting the leftovers afterwards cannot express a RENAMED
     * preId: a renamed child is one row to delete and one to create, and with the delete last the
     * create runs while the old row is still there, so any child model with a business unique key
     * fails on its index. Role.Tenant.json is exactly that shape — renaming
     * {@code role_navigation.employee.employee} to
     * {@code role_navigation.employee.core-hr-employee-employee} left the grant itself untouched, and
     * every re-load died on "This role already has a grant for this navigation", naming a duplicate
     * that was never going to exist once the delete ran.
     *
     * <p>Deleting first is safe in a way the reverse is not: the whole load is one transaction, so a
     * failure anywhere puts the deleted rows back, and "survives" is decided by the file, not by
     * write side effects. Only children that came from a seed are candidates — they carry a binding.
     * A child added by hand has none and is kept: the file never said anything about it. An additive or
     * append load deletes nothing at all. A frozen record never reaches here.
     *
     * @param model Main model name
     * @param mainId Main model row ID
     * @param oneToManyMap OneToMany { fieldName: data list} mapping relationship, the value must be a list type.
     */
    private void loadOneToManyRows(String model, Serializable mainId, Map<String, Object> oneToManyMap) {
        oneToManyMap.forEach((field, value) -> {
            Assert.isTrue(value instanceof Collection,
                    "The data of OneToMany field {0}:{1} must be a list: {2}", model, field, value);
            MetaField relation = ModelManager.getModelField(model, field);
            String childModel = relation.getRelatedModel();
            List<Map<String, Object>> childRows = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                Assert.isTrue(item instanceof Map,
                        "The single predefined data of the OneToMany field {0}:{1} must be in Map format: {2}",
                        model, field, item);
                // Copy the child row and inject the back-reference to the main row, leaving the parsed input untouched.
                Map<String, Object> childRow = new LinkedHashMap<>(Cast.<Map<String, Object>>of(item));
                childRow.put(relation.getRelatedField(), mainId);
                childRows.add(childRow);
            }
            if (overwriting()) {
                deleteUndeclaredSeededChildren(childModel, relation.getRelatedField(), mainId, childRows);
            }
            childRows.forEach(childRow -> handlePredefinedData(childModel, childRow));
        });
    }

    /**
     * Delete the children under a main row that came from a seed and that the file no longer declares,
     * together with their bindings.
     *
     * @param childModel   the OneToMany's model
     * @param backRefField the child's field pointing at the main row
     * @param mainId       the main row
     * @param childRows    the children the file declares
     */
    private void deleteUndeclaredSeededChildren(String childModel, String backRefField, Serializable mainId,
                                                List<Map<String, Object>> childRows) {
        // The rows the file still declares. A child whose preId has no binding yet contributes
        // nothing here — it is about to be created, and there is no old row of its own to keep.
        List<Serializable> keepIds = boundRowIds(childModel, preIdsOf(childModel, childRows));
        Filters undeclared = new Filters().eq(backRefField, mainId);
        if (!keepIds.isEmpty()) {
            undeclared.notIn(ID, keepIds);
        }
        // Of those, the seeded ones — inactive ones included: a child deactivated on the page is still the
        // seed's, and left behind it would hold its unique key against the row the file declares in its
        // place. Their bindings go with them: a binding left pointing at a deleted row is not inert — the
        // next run finds it and tries to update a row that is gone.
        FlexQuery undeclaredQuery = new FlexQuery(Set.of(ID), undeclared);
        undeclaredQuery.setFilterControl(FilterControl.bypassActiveControl());
        List<Serializable> undeclaredIds = modelService.searchList(childModel, undeclaredQuery).stream()
                .map(child -> (Serializable) child.get(ID))
                .toList();
        List<Serializable> removedIds = seededRowIds(childModel, undeclaredIds);
        if (!removedIds.isEmpty()) {
            modelService.deleteByIds(childModel, removedIds);
            deleteBindings(childModel, removedIds);
        }
    }

    /**
     * The rows among these that have a binding in their model's scope — the ones a seed created.
     *
     * @param model  model name
     * @param rowIds candidate row ids
     * @return the bound ones, in the given order
     */
    private List<Serializable> seededRowIds(String model, List<Serializable> rowIds) {
        if (CollectionUtils.isEmpty(rowIds)) {
            return List.of();
        }
        Filters filters = new Filters()
                .eq(SysPreData::getModel, model)
                .in(SysPreData::getRowId, rowIds.stream().map(String::valueOf).toList());
        Set<String> bound = this.searchList(inScope(filters, bindingScopeOf(model))).stream()
                .map(SysPreData::getRowId)
                .collect(Collectors.toSet());
        return rowIds.stream().filter(id -> bound.contains(String.valueOf(id))).toList();
    }

    /**
     * The preIds of a set of child rows, in file order. Validated here rather than at the write, so a
     * malformed child is refused before anything is deleted.
     *
     * @param model Child model name
     * @param rows Child rows as the file declares them
     * @return their preIds
     */
    private List<String> preIdsOf(String model, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            Assert.isTrue(row.containsKey(ID),
                    "Predefined data for model {0} must include the preID: {1}", model, row);
            Object preId = row.get(ID);
            Assert.isTrue(preId instanceof String,
                    "Model {0} predefined data's preId must be of type String: {1}", model, preId);
            return (String) preId;
        }).toList();
    }

    /**
     * The row ids these preIds are bound to, skipping the ones with no binding — unlike
     * {@link #getOriginalRowIdsByPreIds}, which is a reference resolution and must find every one.
     * Here a missing binding is the ordinary "this child is new" case.
     *
     * @param model Model name
     * @param preIds Predefined IDs
     * @return the bound row ids, typed for the model's key
     */
    private List<Serializable> boundRowIds(String model, List<String> preIds) {
        if (CollectionUtils.isEmpty(preIds)) {
            return List.of();
        }
        return getScopedBindings(model, preIds, bindingScopeOf(model)).stream()
                .map(binding -> (Serializable) IdUtils.formatId(model, binding.getRowId()))
                .toList();
    }

    /**
     * Drop the preId bindings of rows that no longer exist.
     *
     * <p>Scope-exact like every other binding access, and through the same primitive: the scope comes
     * from {@link #bindingScopeOf} for the model being addressed, not from the ambient tenant. So one
     * tenant dropping a seeded child cannot unbind another tenant's copy of it.
     *
     * @param model Model name
     * @param rowIds Ids of the rows that were just deleted
     */
    private void deleteBindings(String model, List<Serializable> rowIds) {
        if (CollectionUtils.isEmpty(rowIds)) {
            return;
        }
        Filters filters = new Filters()
                .eq(SysPreData::getModel, model)
                .in(SysPreData::getRowId, rowIds.stream().map(String::valueOf).toList());
        this.deleteByFilters(inScope(filters, bindingScopeOf(model)));
    }

    /**
     * Narrow a binding query to one scope: tenantId = T selects a tenant's bindings, null the system
     * bindings (tenant_id IS NULL).
     */
    private static Filters inScope(Filters filters, Long tenantId) {
        if (tenantId == null) {
            filters.isNotSet(SysPreData::getTenantId);
        } else {
            filters.eq(SysPreData::getTenantId, tenantId);
        }
        return filters;
    }

    /**
     * Determine whether to create or update predefined data based on whether the main model preId already exists.
     *
     * <p>The binding is passed in rather than looked up here: the caller has already read it to
     * decide whether the record is frozen, and one lookup per seed row is enough.
     *
     * @param model Model name
     * @param row Predefined data record (main-model fields only)
     * @param optionalPreData this preId's binding in its scope, empty when the row is new
     * @return Record ID created or updated, or null when an additive or append load leaves the row out
     */
    private Serializable createOrUpdateData(String model, Map<String, Object> row,
                                            Optional<SysPreData> optionalPreData) {
        if (optionalPreData.isPresent() && appending()) {
            return leaveAsItIs(model, optionalPreData.get());
        }
        if (optionalPreData.isEmpty() && !overwriting()) {
            // A new row hanging off a seeded row the scope deleted — a template column under a template that
            // is gone — would be an orphan the scope never asked for.
            String deleted = deletedReference(model, row);
            if (deleted != null) {
                log.info("Seed row {} of {} left out: it refers to {}, which was deleted here.",
                        row.get(ID), model, deleted);
                return null;
            }
        }
        // Resolve the preIds of ManyToOne, OneToOne, and ManyToMany fields to row IDs (returns a new
        // map; the caller's row is left untouched).
        Map<String, Object> resolved = resolveReferencedPreIds(model, row);
        if (optionalPreData.isEmpty()) {
            // The seed's `id` is the preId (tracking key). For an EXTERNAL_ID model it is ALSO the
            // row's primary key (code-as-id), so it must stay in the row — IdProcessor
            // requires a non-empty id for EXTERNAL_ID. For generated-id strategies it is tracking-only
            // and removed so the strategy assigns the surrogate id.
            String preId = ModelManager.getIdStrategy(model) == IdStrategy.EXTERNAL_ID
                    ? (String) resolved.get(ID)
                    : (String) resolved.remove(ID);
            Serializable rowId = modelService.createOne(model, resolved);
            generatePreData(model, preId, rowId);
            recordCreated(model, rowId);
            return rowId;
        } else {
            SysPreData preData = optionalPreData.get();
            // Update the data and return the data ID
            Serializable rowId = IdUtils.formatId(model, preData.getRowId());
            if (ModelManager.isTimelineModel(model)) {
                // A timeline entity is several rows — one per version, each with its own sliceId — and
                // the binding names the entity, not a version. The seed row says which version it is by
                // its effective start date.
                if (rowNotExists(model, rowId)) {
                    return overwriting() ? recreateBoundRow(model, resolved, preData) : leaveDeleted(model, preData);
                }
                writeTimelineVersion(model, rowId, preData.getPreId(), resolved);
                recordUpdated(model, rowId);
                return rowId;
            }
            // Overwriting, the update payload is `resolved` plus a null for every updatable field the file
            // leaves out — "clear what the seed no longer says". Additive, it is `resolved` alone. Held apart
            // from `resolved` because the recreate path below must not inherit those nulls: a default value
            // is filled only when the key is ABSENT (BaseProcessor's computeIfAbsent), so creating from the
            // cleared map would write nulls over the model's defaults and produce a row a first load never
            // would.
            Map<String, Object> updatePayload = new LinkedHashMap<>(resolved);
            updatePayload.put(ID, rowId);
            if (overwriting()) {
                Set<String> clearedFields = ModelManager.getModelUpdatableFieldsWithoutXToMany(model);
                clearedFields.removeAll(updatePayload.keySet());
                clearedFields.forEach(fieldName -> updatePayload.put(fieldName, null));
            }
            // A row named by its id alone — a patch reaching a parent only to add children under it — has
            // nothing to write.
            boolean result = updatePayload.size() > 1 && modelService.updateOne(model, updatePayload);
            if (!result && rowNotExists(model, rowId)) {
                if (!overwriting()) {
                    return leaveDeleted(model, preData);
                }
                // The binding outlived the row it points at. That is a normal state, not corruption:
                // seeded rows are ordinary business data afterwards — the role wizard rewrites a
                // role's data scopes, an admin deletes a navigation — and nothing tells sys_pre_data.
                // Failing here made the seed permanently un-re-appliable the moment anyone touched
                // the data. Re-create the row and re-point the binding instead, which is what
                // create-or-update already does for a preId with no binding; this is the same case one
                // step later. (A soft-deleted row counts as gone and is re-created rather than revived.)
                //
                // `result` alone is not enough to conclude the row is missing: updateOne also returns
                // false when nothing changed, which is why the row is probed before recreating — past
                // active control, see rowExists.
                return recreateBoundRow(model, resolved, preData);
            }
            if (result) {
                recordUpdated(model, rowId);
            }
            // The typed id, not `preData.getRowId()` — that column is a String. The caller injects this
            // value into each OneToMany child as the back-reference, and resolveReferencedPreIds reads a
            // String on a to-one field as a preId: a raw row id would be looked up in sys_pre_data, found
            // missing, and reported as "the preIDs … do not exist". Only the UPDATE branch could return
            // the untyped value, so a seed carrying children loaded once and failed on every re-run.
            return rowId;
        }
    }

    /**
     * An append load meeting a row the scope has: nothing is written to it, so what the scope changed in it
     * stands, and so does what the file now says differently. The rows nested in it are still met one by one,
     * and the new ones among them created under it. A seeded row the scope deleted stays deleted.
     *
     * @return the row id, or null when the scope deleted the row — the rows nested in it are left out with it
     */
    private Serializable leaveAsItIs(String model, SysPreData preData) {
        Serializable rowId = IdUtils.formatId(model, preData.getRowId());
        return rowNotExists(model, rowId) ? leaveDeleted(model, preData) : rowId;
    }

    /**
     * An additive or append load meeting a seeded row the scope has since deleted: the deletion stands.
     *
     * @return null — the row and the rows nested in it are left out
     */
    private Serializable leaveDeleted(String model, SysPreData preData) {
        log.info("Seed row {} of {} left out: it was deleted here.", preData.getPreId(), model);
        return null;
    }

    /**
     * The first to-one reference of a row that names a seeded row since deleted in its scope, as
     * {@code Model:preId}, or null. A reference with no binding at all is not this case: resolving it
     * fails the load, as it always has.
     */
    private String deletedReference(String model, Map<String, Object> row) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (ID.equals(entry.getKey()) || !(entry.getValue() instanceof String preId)) {
                continue;
            }
            MetaField field = ModelManager.getModelField(model, entry.getKey());
            if (!FieldType.TO_ONE_TYPES.contains(field.getFieldType())) {
                continue;
            }
            String related = field.getRelatedModel();
            Optional<SysPreData> binding = getScopedBindings(related, List.of(preId), referenceScopeOf(related))
                    .stream().findFirst();
            if (binding.isPresent()
                    && rowNotExists(related, IdUtils.formatId(related, binding.get().getRowId()))) {
                return related + ":" + preId;
            }
        }
        return null;
    }

    /**
     * Whether the row a binding names is still there, active or not.
     *
     * <p>An ordinary read adds {@code active = true} on an active-control model, and through it a seeded row
     * someone deactivated — or one its file declares inactive — looks gone. Taken for gone, a row whose
     * update changed nothing was re-created: a duplicate primary key on a code-as-id model, a second copy
     * on any other. A soft-deleted row still counts as gone. Every version counts on a timeline model.
     */
    private boolean rowNotExists(String model, Serializable rowId) {
        FlexQuery query = new FlexQuery(Set.of(ID), new Filters().eq(ID, rowId)).acrossTimelineData();
        query.setFilterControl(FilterControl.bypassActiveControl());
        query.setLimitSize(1);
        return modelService.searchList(model, query).isEmpty();
    }

    /**
     * Re-create a row whose binding outlived it, and re-point the binding at the new row.
     *
     * <p>Same id rule as the create branch: an EXTERNAL_ID model's id IS its primary key (code-as-id),
     * so the recreated row keeps the id the binding already names. Every other strategy assigns a fresh
     * surrogate, which the binding is re-pointed to.
     */
    private Serializable recreateBoundRow(String model, Map<String, Object> resolved, SysPreData preData) {
        log.warn("Predefined data for model {} ({}) was physically deleted; recreating it and "
                + "re-pointing the binding.", model, preData.getRowId());
        if (ModelManager.getIdStrategy(model) == IdStrategy.EXTERNAL_ID) {
            resolved.put(ID, IdUtils.formatId(model, preData.getRowId()));
        } else {
            resolved.remove(ID);
        }
        Serializable recreatedId = modelService.createOne(model, resolved);
        preData.setRowId(String.valueOf(recreatedId));
        this.updateOne(preData);
        recordCreated(model, recreatedId);
        return recreatedId;
    }

    /**
     * The rows of a timeline file name each version by its entity and start date, and by nothing else. A row
     * without a start date is refused; the slice id is generated and the end date follows from the next
     * version, so a row carrying either is refused rather than half honoured; and two rows naming the same
     * entity and start date — the second would silently overwrite the first — are refused too. All before the
     * file writes anything. The entity is the model's businessKey when it declares
     * one, which is what files a new row under a stored entity, and the row's preId otherwise.
     *
     * <p>Package-private for the tests that drive one file's rows.
     */
    void checkTimelineRows(String model, List<Map<String, Object>> rows) {
        MetaModel metaModel = ModelManager.getModel(model);
        List<String> businessKey = metaModel == null || metaModel.getBusinessKey() == null
                ? List.of() : metaModel.getBusinessKey();
        Map<String, Object> versions = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object preId = row.get(ID);
            for (String computed : List.of(SLICE_ID, EFFECTIVE_END_DATE)) {
                Assert.notTrue(row.containsKey(computed), "Timeline model {0}: seed row {1} carries {2}. A seed row "
                        + "names its version by effectiveStartDate alone: the slice id is generated, and the end "
                        + "date follows from the next version.", model, preId, computed);
            }
            Object startDate = row.get(EFFECTIVE_START_DATE);
            // Refused here, before anything is written: a new row without a date would be created starting on
            // the day it loads, and every later load of the file would then fail on it.
            Assert.notNull(startDate, "Timeline model {0}: seed row {1} has no effectiveStartDate. A timeline "
                    + "seed row describes one version and must say from when it applies.", model, preId);
            List<Object> entity = businessKey.isEmpty()
                    ? List.of(String.valueOf(preId)) : businessKey.stream().map(row::get).toList();
            Object earlier = versions.putIfAbsent(entity + "@" + startDate, preId);
            Assert.isTrue(earlier == null, "Timeline model {0}: seed rows {1} and {2} are the same version of one "
                    + "entity, starting on {3}. The second would overwrite the first.", model, earlier, preId, startDate);
        }
    }

    /**
     * Apply a seed row to the version of a timeline entity it describes.
     *
     * <p>A timeline seed file is the entity's history: one row per version, each with a preId of its own and
     * the date the version starts. The entity is what the preIds share — a row with no binding yet is created,
     * and the framework files it under the stored entity with the same businessKey as a new version — and the
     * start date says which version a row is. So a bound row is written to its version: the stored version
     * starting that day is corrected in place, and otherwise the version is inserted, before, between or after
     * the stored ones (the framework's version insert cuts its neighbours to fit). Versions the file does not
     * describe are left as they are, as rows taken out of a file are.
     *
     * <p>On an overwriting load, fields the file leaves out are cleared, as on any re-load, except the
     * timeline's own: the end date is computed from the next version, so clearing it would make a version
     * that was cut short run on under the next one. An additive load clears nothing.
     */
    private void writeTimelineVersion(String model, Serializable rowId, String preId, Map<String, Object> resolved) {
        LocalDate startDate = DateUtils.dateToLocalDate(resolved.get(EFFECTIVE_START_DATE));
        Assert.notNull(startDate, "Timeline model {0}: seed row {1} has no effectiveStartDate. A timeline "
                + "seed row describes one version and must say from when it applies.", model, preId);
        Map<String, Object> version = new LinkedHashMap<>(resolved);
        version.put(ID, rowId);
        version.put(EFFECTIVE_START_DATE, startDate);
        version.remove(SLICE_ID);
        version.remove(EFFECTIVE_END_DATE);
        if (overwriting()) {
            Set<String> clearedFields = ModelManager.getModelUpdatableFieldsWithoutXToMany(model);
            clearedFields.removeAll(version.keySet());
            clearedFields.removeAll(TIMELINE_FIELDS);
            clearedFields.forEach(fieldName -> version.put(fieldName, null));
        }
        // Same start date: the stored version is corrected in place. Any other: the version is inserted.
        modelService.addVersion(model, version);
    }

    /**
     * Get the SysPreData object by preID.
     * Each tenant owns its own binding for a multi-tenant model's preId, so re-loading the same file
     * under another tenant creates that tenant's rows instead of touching the first tenant's. A shared
     * model's binding is written once, at system scope, whoever triggers the load.
     *
     * @param model Model name
     * @param row Predefined data record
     * @return SysPreData object
     */
    private Optional<SysPreData> getPreDataByPreId(String model, Map<String, Object> row) {
        Assert.isTrue(row.containsKey(ID), "Predefined data for model {0} must include the preID: {1}", model, row);
        Object preId = row.get(ID);
        Assert.isTrue(preId instanceof String, "Model {0} predefined data's preId must be of type String: {1}", model, preId);
        return getScopedBindings(model, List.of((String) preId), bindingScopeOf(model)).stream().findFirst();
    }

    /**
     * The scope a model's bindings live in, derived from the model rather than from the load: a
     * multi-tenant model's rows belong to the current tenant, a shared model's are the one globally
     * visible copy and always bind at system scope. Deriving it from the model — rather than reading
     * the ambient tenant — is what makes a cross-scope reference resolvable: a tenant load asking for
     * a shared model's binding must look under {@code tenant_id IS NULL} despite carrying a tenant.
     *
     * <p>Package-private so the scope decision stays unit-testable without driving a whole file load.
     *
     * @param model Model name whose bindings are being addressed
     * @return the scope's tenant id, null for the system scope
     */
    Long bindingScopeOf(String model) {
        return ModelManager.isMultiTenantModel(model) ? ContextHolder.getContext().getTenantId() : null;
    }

    /**
     * Query the bindings of the given preIds within ONE scope: tenantId = T selects a tenant's
     * bindings, null selects the system bindings (tenant_id IS NULL). The single query primitive
     * behind the idempotency lookup and the reference resolution — every binding access is
     * scope-exact, and the scope always comes from {@link #bindingScopeOf} for the model being
     * addressed.
     *
     * @param model Model name
     * @param preIds Predefined IDs
     * @param tenantId tenant id of the scope, null for the system scope
     * @return bindings found in this scope
     */
    private List<SysPreData> getScopedBindings(String model, List<String> preIds, Long tenantId) {
        Filters filters = new Filters().eq(SysPreData::getModel, model).in(SysPreData::getPreId, preIds);
        return this.searchList(inScope(filters, tenantId));
    }

    /**
     * Resolve the preIds of ManyToOne, OneToOne, and ManyToMany fields to the bound row IDs, returning a
     * NEW row map — the input {@code row} is left unmodified, so the caller owns the resolved copy.
     *
     * @param model Model name
     * @param row Predefined data record
     * @return a copy of {@code row} with reference preIds replaced by row IDs
     */
    private Map<String, Object> resolveReferencedPreIds(String model, Map<String, Object> row) {
        Map<String, Object> resolved = new LinkedHashMap<>(row);
        for (Map.Entry<String, Object> entry : resolved.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            MetaField metaField = ModelManager.getModelField(model, entry.getKey());
            if (FieldType.TO_ONE_TYPES.contains(metaField.getFieldType())) {
                if (!(entry.getValue() instanceof Long || entry.getValue() instanceof Integer)) {
                    Assert.isTrue(entry.getValue() instanceof String,
                            "Model {0} field {1}:{2} preID must be of type String: {3}",
                            model, entry.getKey(), metaField.getFieldType().getType(), entry.getValue());
                    Serializable rowId = this.getOriginalRowIdByPreId(metaField.getRelatedModel(), Cast.of(entry.getValue()));
                    entry.setValue(rowId);
                }
            } else if (FieldType.MANY_TO_MANY.equals(metaField.getFieldType())) {
                Assert.isTrue(entry.getValue() instanceof Collection,
                        "Model {0} predefined data's {1} ManyToMany field value must be a list or empty",
                        model, entry.getKey());
                if (!CollectionUtils.isEmpty((Collection<?>) entry.getValue())) {
                    List<String> preIds = Cast.of(entry.getValue());
                    List<Serializable> rowIds = this.getOriginalRowIdsByPreIds(metaField.getRelatedModel(), preIds);
                    entry.setValue(rowIds);
                }
            }
        }
        return resolved;
    }

    /**
     * The scope to resolve a reference's preId in: {@link #bindingScopeOf} for the referenced model,
     * plus the one combination that has no answer — a multi-tenant model's bindings exist once per
     * tenant, so resolving one of its preIds from a system-scope load would mean picking a tenant and
     * there is none to pick. Reference such a row by its actual id instead; that path needs no binding
     * and {@link #resolveReferencedPreIds} passes it straight through.
     * <p>
     * Direction across the tenancy boundary is deliberately NOT checked: {@code multiTenant} says
     * whether the ORM narrows reads, not who a row belongs to, so a shared platform-side table
     * legitimately points at a tenant's row — {@code SysPreData} itself is one.
     *
     * <p>Package-private so the rule stays unit-testable without driving a whole file load.
     *
     * @param model Model name being referenced
     * @return the scope's tenant id to resolve in, null for the system scope
     */
    Long referenceScopeOf(String model) {
        Long tenantId = bindingScopeOf(model);
        Assert.notTrue(ModelManager.isMultiTenantModel(model) && tenantId == null,
                "Predefined data references multi-tenant model {0} by preID from a system-scope load: its " +
                "bindings exist once per tenant and this load has no tenant to resolve against. Reference " +
                "the row by its actual id instead, or move this seed to data-tenant.", model);
        return tenantId;
    }

    /**
     * Get the model row ID bound by preId.
     * @param model Model name
     * @param preId Predefined ID
     * @return Model row ID
     */
    private Serializable getOriginalRowIdByPreId(String model, String preId) {
        return getOriginalRowIdsByPreIds(model, List.of(preId)).getFirst();
    }

    /**
     * Get the model row IDs bound by preIds, in the order of the input preIds. Resolution is
     * scope-exact like every binding lookup, in the scope of the model being referenced
     * ({@link #referenceScopeOf}) rather than the scope of the load — a tenant seed resolves a shared
     * model's binding at system scope. Every preId must resolve; the missing ones are reported
     * together.
     *
     * @param model Model name being referenced
     * @param preIds Predefined IDs
     * @return List of model row IDs
     */
    private List<Serializable> getOriginalRowIdsByPreIds(String model, List<String> preIds) {
        Long tenantId = referenceScopeOf(model);
        Map<String, Serializable> resolved = new HashMap<>();
        getScopedBindings(model, preIds, tenantId).forEach(binding ->
                resolved.putIfAbsent(binding.getPreId(), IdUtils.formatId(model, binding.getRowId())));
        List<String> missing = preIds.stream().filter(preId -> !resolved.containsKey(preId)).toList();
        Assert.isTrue(missing.isEmpty(), "The preIDs of the predefined data for model {0}: {1} do not exist " +
                "in the predefined data table and may not have been created yet! Referenced data must be " +
                "loaded first — for a shared model that means loading the system seeds before this one.",
                model, missing);
        return preIds.stream().map(resolved::get).toList();
    }

    /**
     * Create predefined data and bind the model row ID.
     *
     * @param model Model name
     * @param preId Predefined ID
     * @param rowId Model record ID
     */
    private void generatePreData(String model, String preId, Serializable rowId) {
        SysPreData preData = new SysPreData();
        preData.setModel(model);
        preData.setPreId(preId);
        preData.setRowId(rowId.toString());
        // Stamp the scope of the model being bound — exactly what fillTenantFieldForInsert put on the
        // seeded row itself, so the binding and the row it points at cannot land in different scopes.
        preData.setTenantId(bindingScopeOf(model));
        this.createOne(preData);
    }
}
