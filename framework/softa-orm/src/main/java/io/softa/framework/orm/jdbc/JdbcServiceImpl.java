package io.softa.framework.orm.jdbc;

import java.io.Serializable;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.exception.VersionException;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.orm.annotation.RPCCheckpoint;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.changelog.ChangeLogPublisher;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.jdbc.database.SqlBuilderFactory;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.jdbc.database.StaticSqlBuilder;
import io.softa.framework.orm.jdbc.pipeline.DataCreatePipeline;
import io.softa.framework.orm.jdbc.pipeline.DataReadPipeline;
import io.softa.framework.orm.jdbc.pipeline.DataUpdatePipeline;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.framework.orm.utils.IdUtils;

/**
 * JdbcServiceImpl
 * Don't check permissions at this layer, the upper layer has already checked permissions.
 *
 * @param <K> Primary key type
 */
@Slf4j
@Service
@RPCCheckpoint
public class JdbcServiceImpl<K extends Serializable> implements JdbcService<K> {

    @Autowired
    private JdbcProxy jdbcProxy;

    @Autowired
    private ChangeLogPublisher changeLogPublisher;

    /**
     * Extracts all values of the List<Map> structure in the order of Fields and returns an array list.
     *
     * @param fields Field name list
     * @param rows Data list
     * @return List of field value arrays
     */
    private List<Object[]> getBatchValues(List<String> fields, List<Map<String, Object>> rows) {
        // The list of field value arrays
        List<Object[]> batchValues = new ArrayList<>(rows.size());
        for (Map<String, Object> value : rows) {
            Object[] values = new Object[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                values[i] = value.get(fields.get(i));
            }
            batchValues.add(values);
        }
        return batchValues;
    }

    /**
     * Performs a batch insertion of multiple rows into the specified model,
     * returning the inserted data (including any auto-generated fields).
     *
     * @param modelName the name of the model
     * @param rows a list of rows to be inserted
     * @return a list of maps representing the inserted rows
     */
    @SkipPermissionCheck
    public List<Map<String, Object>> insertList(String modelName, List<Map<String, Object>> rows) {
        LocalDateTime insertTime = LocalDateTime.now();
        DataCreatePipeline pipeline = new DataCreatePipeline(modelName);
        // Before anything is written: a File field may only name a file this row is entitled to hold.
        // Read-side expansion trusts the column outright, so this is where that trust is earned. The
        // plan carries what the check read, so the claim below does not read it again.
        FileOwnership.Plan filePlan = FileOwnership.validate(modelName, rows);
        // Format the data, fill in the audit fields,
        // and fill in the tenantId field according to whether it is a multi-tenant model.
        rows = pipeline.processCreateData(rows, insertTime);
        List<String> fields = new ArrayList<>(pipeline.getStoredFields());
        // Get SQL
        SqlParams sqlParams = StaticSqlBuilder.getInsertSql(modelName, fields);
        IdStrategy idStrategy = ModelManager.getIdStrategy(modelName);
        if (IdStrategy.DB_AUTO_ID.equals(idStrategy)) {
            // When using database auto-increment id, the JDBCTemplate needs to be created one by one
            rows.forEach(row -> {
                List<Object> valueList = fields.stream().map(row::get).collect(Collectors.toList());
                sqlParams.setArgs(valueList);
                Serializable id = jdbcProxy.insert(modelName, sqlParams);
                row.put(ModelConstant.ID, id);
            });
        } else {
            // When the idStrategy is not DB_AUTO_ID, the id in the data has been assigned a value by default,
            // and the insert can be batch executed without the database returning ids
            List<Object[]> batchValues = getBatchValues(fields, rows);
            jdbcProxy.batchUpdate(modelName, sqlParams, batchValues);
        }
        // Collect changeLogs after filling in the id
        changeLogPublisher.publishCreationLog(modelName, rows, insertTime);
        // Because its need to get the ids first, and finally process the OneToMany, ManyToMany parameters
        // to create associated table or intermediate table rows.
        pipeline.processXToManyData(rows);
        // Same reason, for files: an upload from a create form has no row to name yet, so the record it
        // wrote points at nothing until here. Binding it is what lets the next write's validate() know
        // who owns this file.
        FileOwnership.claimCreated(modelName, rows, filePlan);
        return rows;
    }

    /**
     * Queries multiple rows by their IDs. If no fields are specified,
     * all accessible fields are returned by default.
     *
     * @param modelName the name of the model
     * @param ids a list of row IDs
     * @param fields the list of fields to retrieve; if null or empty, retrieves all accessible fields
     * @param convertType the type of data conversion applied to the result
     * @return a list of maps representing the retrieved rows
     */
    public List<Map<String, Object>> selectByIds(String modelName, List<K> ids, List<String> fields, ConvertType convertType) {
        String primaryKey = ModelManager.getModelPrimaryKey(modelName);
        if (CollectionUtils.isEmpty(fields)) {
            fields = ModelManager.getModelStoredFields(modelName);
        } else if (!fields.contains(primaryKey)) {
            fields.add(primaryKey);
            fields.add(ModelConstant.ID);
        }
        SqlParams sqlParams = StaticSqlBuilder.getSelectSql(modelName, fields, ids);
        List<Map<String, Object>> rows = jdbcProxy.queryForList(modelName, sqlParams);
        if (CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        // Whether to format the data, including data decryption, convert data, and fill in relation fields
        if (ConvertType.ORIGINAL.equals(convertType)) {
            // Format id value
            IdStrategy idStrategy = ModelManager.getIdStrategy(modelName);
            if (IdStrategy.DISTRIBUTED_LONG.equals(idStrategy)) {
                // Convert id to Long type if generated by Long strategies.
                MetaField idField = ModelManager.getModelPrimaryKeyField(modelName);
                if (FieldType.STRING.equals(idField.getFieldType())) {
                    rows.forEach(row -> row.put(primaryKey, IdUtils.convertIdToString(row.get(primaryKey))));
                }
            }
        } else {
            FlexQuery flexQuery = new FlexQuery(fields);
            flexQuery.setConvertType(convertType);
            DataReadPipeline dataPipeline = new DataReadPipeline(modelName, flexQuery);
            dataPipeline.processReadData(rows);
        }
        return rows;
    }

    /**
     * Queries multiple rows based on a given {@link FlexQuery}.
     *
     * @param modelName the name of the model
     * @param flexQuery a {@link FlexQuery} defining filters, fields, sorting, etc.
     * @return a list of maps representing the filtered rows
     */
    public List<Map<String, Object>> selectByFilter(String modelName, FlexQuery flexQuery) {
        SqlParams sqlParams;
        if (flexQuery.getTopN() != null && flexQuery.getTopN() > 0) {
            sqlParams = SqlBuilderFactory.buildTopNSql(modelName, flexQuery);
        } else {
            sqlParams = SqlBuilderFactory.buildSelectSql(modelName, flexQuery);
        }
        List<Map<String, Object>> rows = jdbcProxy.queryForList(modelName, sqlParams);
        if (CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        if (!ConvertType.ORIGINAL.equals(flexQuery.getConvertType())) {
            DataReadPipeline dataPipeline = new DataReadPipeline(modelName, flexQuery);
            dataPipeline.processReadData(rows);
        }
        return rows;
    }

    /**
     * Get a list of IDs based on a given {@link FlexQuery}.
     * <p>If {@code fieldName} references the model's own primary key, retrieves IDs from the current model.
     * If it references a ManyToOne or OneToOne field, retrieves the related entity IDs.</p>
     *
     * @param <EK> the type of the retrieved IDs
     * @param modelName the name of the model
     * @param fieldName the field name whose IDs are retrieved
     * @param flexQuery a {@link FlexQuery} defining filters, sorting, etc.
     * @return a list of IDs of type {@code EK}
     */
    public <EK extends Serializable> List<EK> getIds(String modelName, String fieldName, FlexQuery flexQuery) {
        flexQuery.setFields(Sets.newHashSet(fieldName));
        SqlParams sqlParams = SqlBuilderFactory.buildSelectSql(modelName, flexQuery);
        List<Map<String, Object>> rows = jdbcProxy.queryForList(modelName, sqlParams);
        List<EK> ids = Cast.of(rows.stream()
                .map(row -> row.get(fieldName))
                .collect(Collectors.toList()));
        return IdUtils.formatIds(modelName, fieldName, ids);
    }

    /**
     * Checks whether a row with the specified ID exists in the data store,
     * without performing any permission checks.
     *
     * @param modelName the name of the model
     * @param id the unique ID of the row
     * @return {@code true} if the row physically exists; otherwise {@code false}
     */
    public boolean exist(String modelName, Serializable id) {
        long count = this.count(modelName, new FlexQuery(new Filters().eq(ModelConstant.ID, id)));
        return count > 0;
    }

    /**
     * Performs a paginated query based on a given {@link FlexQuery},
     * returning a {@link Page} of rows.
     *
     * @param modelName the name of the model
     * @param flexQuery a {@link FlexQuery} defining filters, fields, sorting, etc.
     * @param page the {@link Page} containing pagination parameters
     * @return a {@link Page} of maps representing the queried rows
     */
    public Page<Map<String, Object>> selectByPage(String modelName, FlexQuery flexQuery, Page<Map<String, Object>> page) {
        if (page.isCount()) {
            long totalCount = this.count(modelName, flexQuery);
            page.setTotalCount(totalCount);
            if (totalCount == 0) {
                return page.setRows(Collections.emptyList());
            }
        }
        SqlParams sqlParams = SqlBuilderFactory.buildPageSql(modelName, flexQuery, page);
        List<Map<String, Object>> rows = jdbcProxy.queryForList(modelName, sqlParams);
        if (CollectionUtils.isEmpty(rows)) {
            // A loop reads every page into the same page object. An empty read that left the rows of the
            // page before in place had the loop handle them twice and, as they filled the page, go on forever.
            return page.setRows(Collections.emptyList());
        }
        // Whether to format the data, including data decryption, convert data, and fill in relation fields
        if (!ConvertType.ORIGINAL.equals(flexQuery.getConvertType())) {
            DataReadPipeline dataPipeline = new DataReadPipeline(modelName, flexQuery);
            dataPipeline.processReadData(rows);
        }
        return page.setRows(rows);
    }

    /**
     * Updates a single row identified by its ID, without performing any data conversion
     * or automatic field population. Also triggers the audit fields and change log as needed.
     * <p>On a versionLock model the write moves the version on, and a row carrying {@code version}
     * (the version it was read at) is written only at that version, else {@link VersionException}.</p>
     * <p>Use with caution or in restricted scenarios.</p>
     *
     * @param modelName the name of the model
     * @param rowMap the data to update, including the row's primary key
     * @return the number of rows affected
     */
    public Integer updateOne(String modelName, Map<String, Object> rowMap) {
        SqlParams sqlParams = StaticSqlBuilder.getUpdateSql(modelName, rowMap);
        int result = jdbcProxy.update(modelName, sqlParams);
        if (result == 0 && StaticSqlBuilder.isVersionGuarded(modelName, rowMap.keySet())) {
            throw versionMismatch(rowMap.get(ModelConstant.VERSION));
        }
        return result;
    }

    /**
     * Writes pk-keyed rows the way {@link #updateOne} writes one, without a round trip per row:
     * rows that set the same fields share one UPDATE statement, so each such group goes to the
     * database as JDBC batches of {@code DEFAULT_BATCH_SIZE}. A row alone in its group is still
     * a plain {@link #updateOne}.
     * <p>A row naming nothing but its pk is skipped: it has nothing to set. A call that names the
     * same pk more than once is written row by row in the order given, because grouping would
     * reorder two patches of one row.</p>
     *
     * @param modelName the name of the model
     * @param rows rows to write, each carrying its pk and the fields to set
     * @return the number of rows affected
     */
    int updateRows(String modelName, List<Map<String, Object>> rows) {
        String pk = ModelManager.getModelPrimaryKey(modelName);
        // Written, such a row would be `SET WHERE id = ?`, or a bare version bump under the lock.
        List<Map<String, Object>> toWrite = rows.stream()
                .filter(row -> row.keySet().stream().anyMatch(field -> !pk.equals(field)))
                .toList();
        // Grouped by shape, the later of two patches of one row could go out first and lose.
        Set<Object> pks = new HashSet<>();
        if (toWrite.stream().anyMatch(row -> !pks.add(row.get(pk)))) {
            return toWrite.stream().mapToInt(row -> updateOne(modelName, row)).sum();
        }
        // The sorted field list is the statement's shape. It also decides the version condition,
        // which applies exactly when the rows carry `version`, as in updateOne.
        Map<List<String>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : toWrite) {
            List<String> fields = row.keySet().stream().filter(field -> !pk.equals(field)).sorted().toList();
            groups.computeIfAbsent(fields, key -> new ArrayList<>()).add(row);
        }
        int count = 0;
        for (Map.Entry<List<String>, List<Map<String, Object>>> group : groups.entrySet()) {
            List<Map<String, Object>> groupRows = group.getValue();
            count += groupRows.size() == 1
                    ? updateOne(modelName, groupRows.getFirst())
                    : updateInBatches(modelName, group.getKey(), groupRows);
        }
        return count;
    }

    /**
     * Writes rows that set the same fields with one statement, in JDBC batches of
     * {@code DEFAULT_BATCH_SIZE}. Rows carrying {@code version} are each checked as {@link #updateOne}
     * checks one: a row that matched nothing was modified since it was read.
     *
     * @param modelName the name of the model
     * @param fields the fields every row sets, sorted, without the pk
     * @param rows rows to write
     * @return the number of rows affected
     */
    private int updateInBatches(String modelName, List<String> fields, List<Map<String, Object>> rows) {
        boolean versionGuarded = StaticSqlBuilder.isVersionGuarded(modelName, fields);
        SqlParams sqlParams = StaticSqlBuilder.getUpdateSql(modelName, fields);
        int count = 0;
        for (List<Map<String, Object>> batch : Lists.partition(rows, BaseConstant.DEFAULT_BATCH_SIZE)) {
            List<Object[]> batchArgs = batch.stream()
                    .map(row -> StaticSqlBuilder.getUpdateArgs(modelName, fields, row))
                    .toList();
            int[] results = jdbcProxy.batchUpdateCounts(modelName, sqlParams, batchArgs);
            for (int i = 0; i < results.length; i++) {
                if (versionGuarded && results[i] == Statement.SUCCESS_NO_INFO) {
                    // The batch is written, but whether this row matched its version is unknown, and
                    // passing it could hide a lost update. MySQL and PostgreSQL report UPDATE counts.
                    throw new SystemException("""
                            The JDBC driver reported no update count (SUCCESS_NO_INFO) for a batch update \
                            of the version-locked model {0}, so the optimistic lock cannot be verified.""",
                            modelName);
                } else if (versionGuarded && results[i] == 0) {
                    throw versionMismatch(batch.get(i).get(ModelConstant.VERSION));
                }
                // Without the version condition, a row the driver cannot account for counts as written.
                count += results[i] == Statement.SUCCESS_NO_INFO ? 1 : results[i];
            }
        }
        return count;
    }

    /** The optimistic-lock failure of a row whose stored version is no longer the one it was read at. */
    private static VersionException versionMismatch(Object readVersion) {
        return new VersionException("""
                Data version does not match, may have been modified, please refresh and try again!
                Provided value: {0}, but the data has been modified since it was read.""", readVersion);
    }

    /**
     * Performs a batch update of multiple rows by their IDs. Each row can
     * contain a different set of fields. Only the specified fields in
     * {@code toUpdateFields} will be updated if provided.
     *
     * @param modelName the name of the model
     * @param rows the list of rows to be updated
     * @param toUpdateFields a set of field names to update; if null/empty, updates all provided fields
     * @return the number of rows affected
     */
    @SkipPermissionCheck
    public Integer updateList(String modelName, List<Map<String, Object>> rows, Set<String> toUpdateFields) {
        LocalDateTime updatedTime = LocalDateTime.now();
        // Same guard as create, and it matters as much: replacing an attachment writes a new id into a
        // column an ordinary user controls.
        FileOwnership.Plan filePlan = FileOwnership.validate(modelName, rows);
        DataUpdatePipeline pipeline = new DataUpdatePipeline(modelName, toUpdateFields);
        // TODO: Process according to the `enableChangeLog` config, referring the TODO in ChangeLogPublisher.class
        //  if enableChangeLog = false, there is no need to get original data, compare differences and collect changeLogs.
        Set<String> logContext = this.changeLogContextFields(modelName, pipeline.getDifferFields());
        Set<String> readFields = new HashSet<>(pipeline.getDifferFields());
        if (!readFields.isEmpty()) {
            readFields.addAll(logContext);
        }
        Map<Serializable, Map<String, Object>> originalRowsMap = this.getOriginalRowMap(modelName, rows, readFields);
        // The pipeline sees exactly the originals it always did: the log's context columns ride along
        // in the same query for the change log only, and must not reach the merge and constraint
        // checks the update runs on what it read.
        //
        // Always a copy, also when there is no context to take out: the diff replaces each original
        // it is handed with only the columns that changed, and the log reads the originals after it.
        // Sharing the map left the log with the changed columns alone — no reference, no name — on
        // every update whose context columns were all among the ones it already reads (a child row
        // written through its parent carries the FK, and a condition can pull in the rest).
        Map<Serializable, Map<String, Object>> pipelineOriginals =
                originalsForPipeline(originalRowsMap, logContext, pipeline.getDifferFields());
        // Get the list of changed data, keeping only the fields and row data that have changed.
        List<Map<String, Object>> differRows = pipeline.processUpdateData(rows, pipelineOriginals, updatedTime);
        int count = this.updateRows(modelName, differRows);
        // After updating the main table, update the sub-table to avoid the sub-table cascade field being the old value.
        boolean changed = pipeline.processXToManyData(rows);
        // Re-bind what the write now references, and release what it stopped referencing — a cleared
        // attachment must stop being reachable through the row it used to hang on.
        FileOwnership.claimUpdated(modelName, rows, filePlan);
        if (count > 0) {
            // Collect changeLogs
            changeLogPublisher.publishUpdateLog(modelName, differRows, originalRowsMap, updatedTime);
        } else if (changed) {
            // If the main table is not updated, but the sub-table is updated, mark the main table as updated
            return 1;
        }
        return count;
    }

    /**
     * Get the original data value.
     *
     * @param modelName Model name
     * @param rows Data list
     * @param differFields Fields that have changed
     * @return Map of original data
     */
    /**
     * Columns an update log carries besides what the update wrote: the row's many-to-one references
     * and its display-name fields.
     *
     * <p>An update log otherwise holds only the columns written, so it can say neither which record
     * the row belongs to nor what the row is called. Both matter once the log is read from the
     * parent's side — a family member's renamed relationship has to be found under its employee,
     * and listed as that family member rather than as "a row". Read in the same query as the
     * originals, so the change log costs no extra round trip; nothing when change logs are off.
     */
    Set<String> changeLogContextFields(String modelName, Set<String> differFields) {
        if (!SystemConfig.env.isEnableChangeLog() || differFields.isEmpty()
                || !ModelManager.isAuditLogEnabled(modelName)) {
            return Set.of();
        }
        Set<String> context = new HashSet<>();
        Set<String> stored = new HashSet<>(ModelManager.getModelStoredFields(modelName));
        for (MetaField field : ModelManager.getModelFields(modelName)) {
            if (FieldType.MANY_TO_ONE.equals(field.getFieldType()) && stored.contains(field.getFieldName())) {
                context.add(field.getFieldName());
            }
        }
        List<String> displayName = ModelManager.getModel(modelName).getDisplayName();
        if (displayName != null) {
            displayName.stream().filter(stored::contains).forEach(context::add);
        }
        context.removeAll(differFields);
        context.remove(ModelConstant.ID);
        return context;
    }

    /**
     * What the update pipeline is handed of the originals: always its own copy, without the change
     * log's context columns. The originals themselves stay whole for the log.
     *
     * <p>Its own method, rather than a call to {@link #withoutKeys} inline, so the choice can be
     * tested against the real diff: handing over the log's map when there is nothing to take out is
     * exactly the mistake that left the log with the changed columns alone.
     */
    static Map<Serializable, Map<String, Object>> originalsForPipeline(
            Map<Serializable, Map<String, Object>> originals, Set<String> logContext, Set<String> differFields) {
        return withoutKeys(originals, logContext, differFields);
    }

    /** A copy of the originals with the change log's context columns taken back out. */
    static Map<Serializable, Map<String, Object>> withoutKeys(
            Map<Serializable, Map<String, Object>> rows, Set<String> keys, Set<String> keep) {
        Map<Serializable, Map<String, Object>> stripped = new HashMap<>(rows.size());
        rows.forEach((pKey, row) -> {
            Map<String, Object> copy = new HashMap<>(row);
            keys.stream().filter(key -> !keep.contains(key)).forEach(copy::remove);
            stripped.put(pKey, copy);
        });
        return stripped;
    }

    private Map<Serializable, Map<String, Object>> getOriginalRowMap(String modelName, List<Map<String, Object>> rows, Set<String> differFields) {
        // TODO: Extract to the upper layer, perform permission check in this method, and query the database one less time.
        // The pk values identify the PHYSICAL rows, so the originals are fetched by the
        // primary-key COLUMN (`id`; `slice_id` for timeline models). selectByIds is not
        // usable here: it queries the logical id column by contract (entity-level reads).
        String primaryKey = ModelManager.getModelPrimaryKey(modelName);
        List<K> pKeys = Cast.of(rows.stream().map(row -> row.get(primaryKey)).collect(Collectors.toList()));
        List<String> readFields = new ArrayList<>(differFields);
        if (readFields.isEmpty()) {
            readFields = ModelManager.getModelStoredFields(modelName);
        } else if (!readFields.contains(primaryKey)) {
            readFields.add(primaryKey);
            readFields.add(ModelConstant.ID);
        }
        SqlParams sqlParams = StaticSqlBuilder.getSelectSql(modelName, readFields, pKeys, primaryKey);
        List<Map<String, Object>> originalRows = jdbcProxy.queryForList(modelName, sqlParams);
        // Get the original data map with the primary key as the key
        return originalRows.stream().collect(Collectors.toMap(map -> (Serializable) map.get(primaryKey), Function.identity()));
    }

    /**
     * Deletes a single slice from a timeline model, identified by its slice ID.
     *
     * @param modelName the name of the model
     * @param sliceId the unique slice ID in a timeline model
     * @return {@code true} if successful; otherwise an exception is thrown
     */
    public boolean deleteBySliceId(String modelName, Serializable sliceId) {
        // Get the original data before deletion for collecting changeLogs
        FlexQuery flexQuery = new FlexQuery(new Filters().eq(ModelConstant.SLICE_ID, sliceId)).acrossTimelineData();
        flexQuery.setConvertType(ConvertType.ORIGINAL);
        List<Map<String, Object>> originalRows = this.selectByFilter(modelName, flexQuery);
        // Physical deletion of timeline slice
        SqlParams sqlParams = StaticSqlBuilder.getDeleteTimelineSliceSql(modelName, sliceId);
        boolean result = jdbcProxy.update(modelName, sqlParams) > 0;
        // Collect changeLogs, changeLogs are bound to the database primary key
        changeLogPublisher.publishDeletionLog(modelName, originalRows, LocalDateTime.now());
        return result;
    }

    /**
     * Deletes multiple rows by their IDs. The {@code deletableRows} parameter is the
     * AUTHORITATIVE deletable row set (for a timeline model: every slice of the logical ids):
     * the soft-delete branch keys its per-row updates off its pk values, and it is also the
     * change-log payload. The {@code ids} drive the physical-delete branch ({@code WHERE id IN},
     * which spans all slices of a timeline entity by the shared id column).
     *
     * @param modelName the name of the model
     * @param ids a list of row IDs to delete
     * @param deletableRows the authoritative rows to delete (per-slice for timeline models)
     * @return {@code true} if successful; otherwise an exception is thrown
     */
    public boolean deleteByIds(String modelName, List<K> ids, List<Map<String, Object>> deletableRows) {
        int count;
        LocalDateTime deleteTime = LocalDateTime.now();
        if (ModelManager.isSoftDeleted(modelName)) {
            // Soft delete: assemble one pk-keyed update row per deletable row (updateRows is the
            // pk-keyed write; the rows all set the same fields, so they go out as one batch).
            // Keying by pk — not by the logical ids — is what covers every slice of a timeline
            // entity (pk = sliceId), mirroring the physical branch below whose `WHERE id IN`
            // naturally spans all slices; for regular models pk = id and the behavior is unchanged.
            String pk = ModelManager.getModelPrimaryKey(modelName);
            List<Map<String, Object>> rows = deletableRows.stream().map(deletableRow -> {
                Map<String, Object> row = new HashMap<>();
                row.put(pk, deletableRow.get(pk));
                row.put(ModelConstant.SOFT_DELETED_FIELD, true);
                // If the model has an active control field, set it to false when soft deleting
                if (ModelManager.isActiveControl(modelName)) {
                    row.put(ModelConstant.ACTIVE_CONTROL_FIELD, false);
                }
                return row;
            }).collect(Collectors.toList());
            AutofillFields.fillAuditFieldsForUpdate(modelName, rows, deleteTime);
            count = this.updateRows(modelName, rows);
        } else {
            // Delete data physically
            SqlParams sqlParams = StaticSqlBuilder.getDeleteSql(modelName, ids);
            count = jdbcProxy.update(modelName, sqlParams);
        }
        // Collect changeLogs
        changeLogPublisher.publishDeletionLog(modelName, deletableRows, deleteTime);
        return count > 0;
    }

    /**
     * Counts the number of rows matching the given {@link FlexQuery}.
     *
     * @param modelName the name of the model
     * @param flexQuery a {@link FlexQuery} defining filters, fields, etc.
     * @return the total count of matching rows
     */
    @Override
    public long count(String modelName, FlexQuery flexQuery) {
        SqlParams sqlParams = SqlBuilderFactory.buildCountSql(modelName, flexQuery);
        Long count = (Long) jdbcProxy.queryForObject(modelName, sqlParams, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * Retrieves data as a list of entities of the specified class,
     * not relying on any metadata from the model manager.
     *
     * @param <T> the type of the entity
     * @param entityClass the target entity class
     * @param orderBy an optional "ORDER BY" clause
     * @return a list of entities
     */
    public <T> List<T> selectMetaEntityList(Class<T> entityClass, String orderBy) {
        String modelName = entityClass.getSimpleName();
        SqlParams sqlParams = StaticSqlBuilder.getSelectAllMetaSql(modelName, orderBy);
        List<Map<String, Object>> rows = jdbcProxy.queryForList(modelName, sqlParams);
        // Convert the original value of the database to an object
        return BeanTool.originalMapListToObjects(rows, entityClass, false);
    }

    /**
     * Retrieves data as a list of entities of the specified class from the given model,
     * not relying on any metadata from the model manager.
     *
     * @param <T> the type of the entity
     * @param modelName the name of the model
     * @param entityClass the target entity class
     * @param orderBy an optional "ORDER BY" clause
     * @return a list of entities
     */
    public <T> List<T> selectMetaEntityList(String modelName, Class<T> entityClass, String orderBy) {
        SqlParams sqlParams = StaticSqlBuilder.getSelectAllMetaSql(modelName, orderBy);
        List<Map<String, Object>> rows = jdbcProxy.queryForList(modelName, sqlParams);
        // Convert the original value of the database to an object
        return BeanTool.originalMapListToObjects(rows, entityClass, true);
    }
}
