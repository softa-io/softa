package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.domain.FileObject;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.FileUtils;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.seed.SeedPushScope;
import io.softa.starter.metadata.seed.SyncErrors;
import io.softa.starter.metadata.seed.TenantSeedFileResult;
import io.softa.starter.metadata.service.SysPreDataService;
import lombok.extern.slf4j.Slf4j;

import static io.softa.framework.orm.constant.ModelConstant.EFFECTIVE_END_DATE;
import static io.softa.framework.orm.constant.ModelConstant.EFFECTIVE_START_DATE;
import static io.softa.framework.orm.constant.ModelConstant.ID;
import static io.softa.framework.orm.constant.ModelConstant.SLICE_ID;
import static io.softa.framework.orm.constant.ModelConstant.TIMELINE_FIELDS;

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
 */
@Slf4j
@Service
public class SysPreDataServiceImpl extends EntityServiceImpl<SysPreData, Long> implements SysPreDataService {

    /**
     * The file whose rows are being loaded, stamped on every binding written for them, so a binding says
     * which file brought its row. Bound around one file's load; unbound outside one.
     */
    private static final ScopedValue<String> SOURCE_FILE = ScopedValue.newInstance();

    private final ModelService<Serializable> modelService;
    private final PreDataFormatParser formatParser = new PreDataFormatParser();

    public SysPreDataServiceImpl(ModelService<Serializable> modelService) {
        this.modelService = modelService;
    }

    /**
     * Load the specified list of predefined data files from the root directory: resources/data.
     * Supports data files in JSON, XML, and CSV formats. Data files support a two-layer domain model,
     * i.e., main model and subModel, but they will be created separately when loading.
     * The main model is created first to generate the main model id, then the subModel data is created.
     *
     * @param fileNames List of relative directory data file names to load
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void loadPreSystemData(List<String> fileNames) {
        String dataDir = BaseConstant.PREDEFINED_DATA_SYSTEM_DIR;
        runAsSystemScope(() -> {
            for (String fileName : fileNames) {
                FileObject fileObject = FileUtils.getFileObjectByPath(dataDir, fileName);
                loadFileObject(fileObject);
            }
        });
    }

    /**
     * Load the specified list of predefined tenant data files from the root directory: resources/data-tenant.
     * Supports data files in JSON, XML, and CSV formats. Data files support a two-layer domain model,
     * i.e., main model and subModel, but they will be created separately when loading.
     * The main model is created first to generate the main model id, then the subModel data is created.
     *
     * @param fileNames List of relative directory tenant data file names to load
     * @param tenantId tenant id to which the data will be loaded
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void loadPreTenantData(List<String> fileNames, Long tenantId) {
        if (SystemConfig.env.isEnableMultiTenancy()) {
            Assert.notNull(tenantId,
                    "Loading tenant predefined data requires a tenant id when multi-tenancy is enabled!");
        }
        loadInTenantScope(BaseConstant.PREDEFINED_DATA_TENANT_DIR, fileNames, tenantId);
    }

    /**
     * Platform-tier load: the tenant loader pointed at {@code data-platform/} with the reserved
     * platform tenant id (-1), so seeded rows are platform-owned and invisible to tenant reads.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void loadPrePlatformData(List<String> fileNames) {
        loadInTenantScope(BaseConstant.PREDEFINED_DATA_PLATFORM_DIR, fileNames,
                BaseConstant.PLATFORM_TENANT_ID);
    }

    private void loadInTenantScope(String dataDir, List<String> fileNames, Long tenantId) {
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
        ContextHolder.runWith(tenantContext, () -> {
            for (String fileName : fileNames) {
                FileObject fileObject = FileUtils.getFileObjectByPath(dataDir, fileName);
                loadFileObject(fileObject);
            }
        });
    }

    /**
     * Loads predefined data from a given multipart file.
     * This method processes the provided multipart file to load predefined data into the system.
     * The file is expected to be in a format recognized by the implementation, such as CSV, JSON, or XML.
     *
     * @param file the multipart file containing the predefined data to be loaded into the system.
     *             The file should not be null and must contain valid data as per the required format.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void loadPreSystemData(MultipartFile file) {
        FileObject fileObject = FileUtils.getFileObject(file);
        runAsSystemScope(() -> loadFileObject(fileObject));
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
        ContextHolder.runWith(systemContext, task);
    }

    /**
     * Parse a file into its {@code modelName -> data} entries (format handling lives in
     * {@link PreDataFormatParser}) and load each model's predefined data in declaration order.
     *
     * @param fileObject fileObject with the file content
     */
    private void loadFileObject(FileObject fileObject) {
        ScopedValue.where(SOURCE_FILE, fileObject.getFileName())
                .run(() -> formatParser.parse(fileObject).forEach(this::processModelData));
    }

    // ─────────────────────── bringing a tenant's copy up to date ───────────────────────

    /** Model and fields of the one push that removes rows: import template columns. */
    private static final String TEMPLATE_MODEL = "ImportTemplate";
    private static final String TEMPLATE_COLUMN_MODEL = "ImportTemplateField";

    @Override
    public Set<String> rowKeysOf(String dataDir, String fileName) {
        Set<String> keys = new LinkedHashSet<>();
        formatParser.parse(FileUtils.getFileObjectByPath(dataDir, fileName)).forEach((model, data) ->
                collectRowKeys(model, rowsOf(model, data), keys));
        return keys;
    }

    private void collectRowKeys(String model, List<Map<String, Object>> rows, Set<String> keys) {
        for (Map<String, Object> row : rows) {
            keys.add(rowKey(model, (String) row.get(ID)));
            row.forEach((field, value) -> {
                if (value instanceof Collection<?> items && !items.isEmpty()
                        && FieldType.ONE_TO_MANY.equals(ModelManager.getModelField(model, field).getFieldType())) {
                    collectRowKeys(ModelManager.getModelField(model, field).getRelatedModel(),
                            rowsOf(model, new ArrayList<>(items)), keys);
                }
            });
        }
    }

    private static String rowKey(String model, String preId) {
        return model + "/" + preId;
    }

    @Override
    public TenantSeedFileResult applyNewRows(String fileName, SeedPushScope push, Set<String> added,
                                             Set<String> removed) {
        Assert.notNull(ContextHolder.getContext().getTenantId(),
                "Bringing tenant seed file {0} up to date needs a tenant context.", fileName);
        Tally tally = new Tally(fileName);
        if (added.isEmpty() && (push != SeedPushScope.INVALID_COLUMNS || removed.isEmpty())) {
            return tally.result();
        }
        FileObject fileObject = FileUtils.getFileObjectByPath(BaseConstant.PREDEFINED_DATA_TENANT_DIR, fileName);
        Map<String, Object> parsed = formatParser.parse(fileObject);
        ScopedValue.where(SOURCE_FILE, fileName).run(() -> {
            parsed.forEach((model, data) -> applyNewRowsOf(model, rowsOf(model, data), push, added, tally));
            if (push == SeedPushScope.INVALID_COLUMNS) {
                removeInvalidColumns(removed, tally);
            }
        });
        return tally.result();
    }

    /**
     * One model's rows of the file that the release added, against the tenant's bindings. A row the tenant
     * has a binding for is the tenant's already — it was set up after the row was added — and is left
     * alone; only a row it never got is written.
     */
    private void applyNewRowsOf(String model, List<Map<String, Object>> rows, SeedPushScope push,
                                Set<String> added, Tally tally) {
        ModelManager.validateModel(model);
        validateSeedScope(model);
        List<Map<String, Object>> newRows = rows.stream()
                .filter(row -> added.contains(rowKey(model, (String) row.get(ID))))
                .toList();
        Map<String, SysPreData> bound = new HashMap<>();
        getScopedBindings(model, preIdsOf(model, rows), bindingScopeOf(model))
                .forEach(binding -> bound.putIfAbsent(binding.getPreId(), binding));
        if (push == SeedPushScope.NEW_NESTED_ITEMS) {
            // The push reaches the rows the tenant already has, whether or not the release touched them.
            for (Map<String, Object> row : rows) {
                SysPreData binding = bound.get((String) row.get(ID));
                if (binding != null) {
                    pushNewNestedItems(model, row, binding, added, tally);
                }
            }
        }
        for (Map<String, Object> row : newRows) {
            String preId = (String) row.get(ID);
            if (bound.containsKey(preId)) {
                tally.skipped++;
                continue;
            }
            Map<String, Object> mainRow = mainFieldsOf(model, row);
            String deletedReference = deletedReference(model, mainRow);
            if (deletedReference != null) {
                // Creating it would hang it off a row the tenant removed — a template column under a
                // template that is gone.
                tally.notes.add(preId + " not created: it refers to " + deletedReference
                        + ", which this tenant deleted");
                tally.skipped++;
                continue;
            }
            Serializable existingId = findByBusinessKey(model, resolveReferencedPreIds(model, mainRow));
            if (existingId != null) {
                // The tenant already has this row under its business key — typically inserted by a
                // release script before the seed carried it. Adopt it as is.
                generatePreData(model, preId, existingId);
                tally.claimed++;
                continue;
            }
            createNewRow(model, row);
            tally.created++;
        }
    }

    /**
     * Create a row the tenant does not have. A failure names the row: the database's message alone says
     * which constraint broke, not which seed row broke it. A unique key the tenant already holds without a
     * binding is not adopted — only a declared business key is — so it fails here, for someone to decide.
     */
    private void createNewRow(String model, Map<String, Object> row) {
        try {
            handlePredefinedData(model, row);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("Row " + row.get(ID) + " of " + model + " could not be created: the "
                    + "tenant already has a " + model + " with the same unique key and no binding to this seed "
                    + "row. " + SyncErrors.rootMessage(e), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Row " + row.get(ID) + " of " + model + " could not be created: "
                    + SyncErrors.rootMessage(e), e);
        }
    }

    /**
     * Add the nested items a row the tenant already has does not have yet — a new option under an
     * existing option set. Items the tenant has, or had and deleted, are left alone, and so are the ones
     * it added itself: unlike a load, nothing the file no longer declares is removed.
     */
    private void pushNewNestedItems(String model, Map<String, Object> row, SysPreData binding, Set<String> added,
                                    Tally tally) {
        Serializable parentId = IdUtils.formatId(model, binding.getRowId());
        if (!modelService.exist(model, parentId)) {
            return;
        }
        row.forEach((field, value) -> {
            MetaField relation = ModelManager.getModelField(model, field);
            if (!FieldType.ONE_TO_MANY.equals(relation.getFieldType()) || !(value instanceof Collection<?> items)) {
                return;
            }
            String childModel = relation.getRelatedModel();
            List<Map<String, Object>> children = new ArrayList<>();
            for (Object item : items) {
                Map<String, Object> child = new LinkedHashMap<>(Cast.<Map<String, Object>>of(item));
                child.put(relation.getRelatedField(), parentId);
                children.add(child);
            }
            Set<String> boundChildren = new HashSet<>();
            getScopedBindings(childModel, preIdsOf(childModel, children), bindingScopeOf(childModel))
                    .forEach(childBinding -> boundChildren.add(childBinding.getPreId()));
            for (Map<String, Object> child : children) {
                String childPreId = (String) child.get(ID);
                if (!added.contains(rowKey(childModel, childPreId)) || boundChildren.contains(childPreId)) {
                    continue;
                }
                Serializable existingId = findByBusinessKey(childModel,
                        resolveReferencedPreIds(childModel, mainFieldsOf(childModel, child)));
                if (existingId != null) {
                    generatePreData(childModel, childPreId, existingId);
                    tally.claimed++;
                } else {
                    createNewRow(childModel, child);
                    tally.pushed++;
                }
            }
        });
    }

    /**
     * Remove the tenant's copies of the import template columns the release dropped from the file, when
     * the field a column reads no longer exists on the template's model: such a column fails every
     * download of the template. Removed even if the tenant renamed its header. A dropped column whose field
     * still exists is kept — it still works — and columns the tenant added are never considered: they have
     * no binding.
     */
    private void removeInvalidColumns(Set<String> removed, Tally tally) {
        if (!ModelManager.existModel(TEMPLATE_COLUMN_MODEL) || !ModelManager.existModel(TEMPLATE_MODEL)) {
            return;
        }
        String prefix = TEMPLATE_COLUMN_MODEL + "/";
        List<String> dropped = removed.stream().filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length())).toList();
        if (dropped.isEmpty()) {
            return;
        }
        for (SysPreData binding : getScopedBindings(TEMPLATE_COLUMN_MODEL, dropped, bindingScopeOf(TEMPLATE_COLUMN_MODEL))) {
            Serializable columnId = IdUtils.formatId(TEMPLATE_COLUMN_MODEL, binding.getRowId());
            Map<String, Object> column = readRow(TEMPLATE_COLUMN_MODEL, columnId, "fieldName", "templateId");
            if (column == null) {
                continue;
            }
            Object templateId = column.get("templateId");
            Map<String, Object> template = templateId == null ? null
                    : readRow(TEMPLATE_MODEL, IdUtils.formatId(TEMPLATE_MODEL, (Serializable) templateId), "modelName");
            String fieldName = (String) column.get("fieldName");
            String modelName = template == null ? null : (String) template.get("modelName");
            if (modelName == null || fieldName == null
                    || ModelManager.existField(modelName, fieldName.split("\\.")[0])) {
                continue;
            }
            modelService.deleteById(TEMPLATE_COLUMN_MODEL, columnId);
            deleteBindings(TEMPLATE_COLUMN_MODEL, List.of(columnId));
            tally.removed++;
        }
    }

    /**
     * The first reference of a row that points at a seed row the tenant has deleted, as {@code Model:preId},
     * or null. A reference with no binding at all is not this case: it fails the load, as it always has.
     */
    private String deletedReference(String model, Map<String, Object> mainRow) {
        for (Map.Entry<String, Object> entry : mainRow.entrySet()) {
            if (!(entry.getValue() instanceof String preId)) {
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
                    && !modelService.exist(related, IdUtils.formatId(related, binding.get().getRowId()))) {
                return related + ":" + preId;
            }
        }
        return null;
    }

    /** The id of the row with the same business key, or null when the model declares none or none matches. */
    private Serializable findByBusinessKey(String model, Map<String, Object> resolved) {
        List<String> businessKey = ModelManager.getModel(model).getBusinessKey();
        if (CollectionUtils.isEmpty(businessKey)) {
            return null;
        }
        Filters filters = new Filters();
        for (String field : businessKey) {
            Object value = resolved.get(field);
            if (value == null) {
                return null;
            }
            filters.eq(field, value);
        }
        List<Serializable> ids = modelService.getIds(model, filters);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Map<String, Object> readRow(String model, Serializable id, String... fields) {
        List<String> selected = new ArrayList<>(List.of(fields));
        selected.add(ID);
        List<Map<String, Object>> rows = modelService.searchList(model,
                new FlexQuery(selected, new Filters().eq(ID, id)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** A row's own fields, without the OneToMany lists nested in it. */
    private static Map<String, Object> mainFieldsOf(String model, Map<String, Object> row) {
        Map<String, Object> main = new LinkedHashMap<>();
        row.forEach((field, value) -> {
            if (!FieldType.ONE_TO_MANY.equals(ModelManager.getModelField(model, field).getFieldType())) {
                main.put(field, value);
            }
        });
        return main;
    }

    /** A model's data in a file as a list of rows: the file may give one row as a map. */
    private static List<Map<String, Object>> rowsOf(String model, Object data) {
        if (data instanceof Map<?, ?> single) {
            return List.of(Cast.of(single));
        }
        Assert.isTrue(data instanceof List<?>, "Model predefined data only supports Map or List<Map> format {0}: {1}",
                model, data);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : (List<?>) data) {
            Assert.isTrue(row instanceof Map, "When defining model data in List structure, the internal data only "
                    + "supports Map format {0}: {1}", model, data);
            rows.add(Cast.of(row));
        }
        return rows;
    }

    /** Counts of one file's application, turned into its result at the end. */
    private static final class Tally {
        private final String file;
        private int created;
        private int claimed;
        private int skipped;
        private int pushed;
        private int removed;
        private final List<String> notes = new ArrayList<>();

        private Tally(String file) {
            this.file = file;
        }

        private TenantSeedFileResult result() {
            return new TenantSeedFileResult(file, created, claimed, skipped, pushed, removed, notes);
        }
    }

    /**
     * Process the model predefined data, which can be a single Map or a List<Map> format.
     *
     * @param model Model name
     * @param predefinedData Predefined data
     */
    private void processModelData(String model, Object predefinedData) {
        ModelManager.validateModel(model);
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
                "Model {0} is multi-tenant: load its predefined data per tenant via loadPreTenantData, " +
                "not as system data.", model);
        Assert.notTrue(tenantScope && !tenantModel,
                "Model {0} is a shared model: load its predefined data via loadPreSystemData, " +
                "not as tenant data.", model);
    }

    /**
     * Reconcile a main row's OneToMany children with the file: the children it still declares are
     * created or updated, the rest are deleted.
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
     * write side effects. Rows the tenant added by hand still go — they carry no binding, so the file
     * does not declare them — which is the pre-existing contract, now applied a step earlier. A
     * frozen record never reaches here at all.
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
            // The rows the file still declares. A child whose preId has no binding yet contributes
            // nothing here — it is about to be created, and there is no old row of its own to keep.
            List<Serializable> keepIds = boundRowIds(childModel, preIdsOf(childModel, childRows));
            Filters deleteFilters = new Filters().eq(relation.getRelatedField(), mainId);
            if (!keepIds.isEmpty()) {
                deleteFilters.notIn(ID, keepIds);
            }
            // Read the doomed ids before the delete: their bindings have to go with them. A binding
            // left pointing at a deleted row is not inert — the next run finds it and tries to update
            // a row that is gone, so dropping a child from the seed would poison every later load.
            List<Serializable> removedIds = modelService.getIds(childModel, deleteFilters);
            modelService.deleteByFilters(childModel, deleteFilters);
            deleteBindings(childModel, removedIds);
            childRows.forEach(childRow -> handlePredefinedData(childModel, childRow));
        });
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
        Long tenantId = bindingScopeOf(model);
        Filters filters = new Filters()
                .eq(SysPreData::getModel, model)
                .in(SysPreData::getRowId, rowIds.stream().map(String::valueOf).toList());
        if (tenantId == null) {
            filters.isNotSet(SysPreData::getTenantId);
        } else {
            filters.eq(SysPreData::getTenantId, tenantId);
        }
        this.deleteByFilters(filters);
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
     * @return Record ID created or updated
     */
    private Serializable createOrUpdateData(String model, Map<String, Object> row,
                                            Optional<SysPreData> optionalPreData) {
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
            return rowId;
        } else {
            SysPreData preData = optionalPreData.get();
            // Update the data and return the data ID
            Serializable rowId = IdUtils.formatId(model, preData.getRowId());
            if (ModelManager.isTimelineModel(model)) {
                // A timeline entity is several rows — one per version, each with its own sliceId — and
                // the binding names the entity, not a version. The seed row says which version it is by
                // its effective start date.
                if (!modelService.exist(model, rowId)) {
                    return recreateBoundRow(model, resolved, preData);
                }
                writeTimelineVersion(model, rowId, preData.getPreId(), resolved);
                restampSource(preData);
                return rowId;
            }
            // The update payload is `resolved` plus a null for every updatable field the file leaves
            // out — "clear what the seed no longer says". Held apart from `resolved` because the
            // recreate path below must not inherit those nulls: a default value is filled only when
            // the key is ABSENT (BaseProcessor's computeIfAbsent), so creating from the cleared map
            // would write nulls over the model's defaults and produce a row a first load never would.
            Map<String, Object> updatePayload = new LinkedHashMap<>(resolved);
            updatePayload.put(ID, rowId);
            Set<String> clearedFields = ModelManager.getModelUpdatableFieldsWithoutXToMany(model);
            clearedFields.removeAll(updatePayload.keySet());
            clearedFields.forEach(fieldName -> updatePayload.put(fieldName, null));
            boolean result = modelService.updateOne(model, updatePayload);
            if (!result && !modelService.exist(model, rowId)) {
                // The binding outlived the row it points at. That is a normal state, not corruption:
                // seeded rows are ordinary business data afterwards — the role wizard rewrites a
                // role's data scopes, an admin deletes a navigation — and nothing tells sys_pre_data.
                // Failing here made the seed permanently un-re-appliable the moment anyone touched
                // the data. Re-create the row and re-point the binding instead, which is what
                // create-or-update already does for a preId with no binding; this is the same case one
                // step later. (`exist` reads through the soft-delete predicate, so a soft-deleted row
                // counts as gone and is re-created rather than revived.)
                //
                // `result` alone is not enough to conclude the row is missing: updateOne also returns
                // false when nothing changed, which is why the row is probed before recreating.
                return recreateBoundRow(model, resolved, preData);
            }
            restampSource(preData);
            // The typed id, not `preData.getRowId()` — that column is a String. The caller injects this
            // value into each OneToMany child as the back-reference, and resolveReferencedPreIds reads a
            // String on a to-one field as a preId: a raw row id would be looked up in sys_pre_data, found
            // missing, and reported as "the preIDs … do not exist". Only the UPDATE branch could return
            // the untyped value, so a seed carrying children loaded once and failed on every re-run.
            return rowId;
        }
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
        if (currentSourceFile() != null) {
            preData.setSourceFile(currentSourceFile());
        }
        this.updateOne(preData);
        return recreatedId;
    }

    /**
     * Point a binding at the file its row was just loaded from, when that is not the one it names — a
     * binding written before files were recorded, or a row that moved to another file with its preId.
     */
    /** The file being loaded, or null outside a file's load. ({@code ScopedValue.orElse} refuses a null.) */
    private static String currentSourceFile() {
        return SOURCE_FILE.isBound() ? SOURCE_FILE.get() : null;
    }

    private void restampSource(SysPreData preData) {
        String sourceFile = currentSourceFile();
        if (sourceFile != null && !sourceFile.equals(preData.getSourceFile())) {
            preData.setSourceFile(sourceFile);
            this.updateOne(preData);
        }
    }

    /**
     * Apply a seed row to the version of a timeline entity it describes.
     *
     * <p>A timeline seed file carries one row per entity: its latest version. The row's effective start
     * date decides what that means for the versions already stored —
     * <ul>
     *   <li>the start date of a stored version: that version is overwritten with the file's values;</li>
     *   <li>later than every stored version: a new version is added from that date, and the one before it
     *       is cut to end the day before (the framework's version insert does both);</li>
     *   <li>anything else — earlier than the latest version and matching none: refused. Versions added
     *       or re-dated since the file was written sit in the way, and whether the file or they are
     *       right is not the loader's call.</li>
     * </ul>
     * Versions the file does not describe are left as they are.
     *
     * <p>Fields the file leaves out are cleared, as on any re-load, except the timeline's own: the end
     * date is computed from the next version, so clearing it would make a version that was cut short
     * run on under the next one.
     */
    private void writeTimelineVersion(String model, Serializable rowId, String preId, Map<String, Object> resolved) {
        LocalDate startDate = DateUtils.dateToLocalDate(resolved.get(EFFECTIVE_START_DATE));
        Assert.notNull(startDate, "Timeline model {0}: seed row {1} has no effectiveStartDate. A timeline "
                + "seed row describes one version and must say from when it applies.", model, preId);
        List<LocalDate> storedStarts = versionStartDates(model, rowId);
        LocalDate latestStart = storedStarts.stream().max(Comparator.naturalOrder()).orElse(null);
        boolean sameVersion = storedStarts.contains(startDate);
        boolean newVersion = latestStart == null || startDate.isAfter(latestStart);
        Assert.isTrue(sameVersion || newVersion, "Timeline model {0}: seed row {1} starts on {2}, which is "
                + "before its latest stored version ({3}) and matches no stored version. Change a version by "
                + "keeping its start date, or add one by giving a date after {3}; a version that has to move "
                + "is a data correction, not a seed change.", model, preId, startDate, latestStart);
        Map<String, Object> version = new LinkedHashMap<>(resolved);
        version.put(ID, rowId);
        version.put(EFFECTIVE_START_DATE, startDate);
        version.remove(SLICE_ID);
        version.remove(EFFECTIVE_END_DATE);
        Set<String> clearedFields = ModelManager.getModelUpdatableFieldsWithoutXToMany(model);
        clearedFields.removeAll(version.keySet());
        clearedFields.removeAll(TIMELINE_FIELDS);
        clearedFields.forEach(fieldName -> version.put(fieldName, null));
        // Same start date: the stored version is corrected in place. Later: a version is inserted after it.
        modelService.addVersion(model, version);
    }

    /** Start dates of every stored version of a timeline entity. */
    private List<LocalDate> versionStartDates(String model, Serializable rowId) {
        FlexQuery query = new FlexQuery(Set.of(SLICE_ID, EFFECTIVE_START_DATE), new Filters().eq(ID, rowId))
                .acrossTimelineData();
        return modelService.searchList(model, query).stream()
                .map(row -> DateUtils.dateToLocalDate(row.get(EFFECTIVE_START_DATE)))
                .filter(Objects::nonNull)
                .toList();
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
        if (tenantId == null) {
            filters.isNotSet(SysPreData::getTenantId);
        } else {
            filters.eq(SysPreData::getTenantId, tenantId);
        }
        return this.searchList(filters);
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
        preData.setSourceFile(currentSourceFile());
        this.createOne(preData);
    }
}
