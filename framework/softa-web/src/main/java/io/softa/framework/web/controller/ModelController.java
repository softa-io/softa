package io.softa.framework.web.controller;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.*;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.dto.*;
import io.softa.framework.web.onchange.FieldOnChangeHandler;
import io.softa.framework.web.onchange.FieldOnChangeRegistry;
import io.softa.framework.web.response.ApiResponse;

/**
 * Model Common Controller.
 * The convertType of ModelController is `REFERENCE` by default,
 * means that the value of expandable fields will be converted to a reference object.
 * The value of the ManyToOne / OneToOne field is a ModelReference object.
 * The value of Option/MultiOption field is a OptionReference object or List<OptionReference> object.
 *
 * @param <K> primary key type
 */
@Tag(name = "Model APIs", description = "Common model APIs, including create, read, update, delete, copy, search, etc.")
@RestController
@RequestMapping("/{modelName}")
public class ModelController<K extends Serializable> {

    @Autowired
    private ModelService<K> modelService;

    @Autowired
    private FieldOnChangeRegistry fieldOnChangeRegistry;

    @Autowired
    private PermissionService permissionService;

    /**
     * The size of operation data in a single API call cannot exceed the MAX_BATCH_SIZE.
     *
     * @param size data size
     */
    private void validateBatchSize(int size) {
        Assert.isTrue(size <= BaseConstant.MAX_BATCH_SIZE,
                "The size of operation data cannot exceed the maximum {0} limit.", BaseConstant.MAX_BATCH_SIZE);
    }

    /**
     * The ids of API request cannot contain null values, and cannot exceed the maximum number limit.
     *
     * @param ids id list
     */
    private void validateIds(List<K> ids) {
        Assert.allNotNull(ids, "ids cannot contain null values: {0}", ids);
        validateBatchSize(ids.size());
    }

    /**
     * Create a single row and return the id.
     *
     * @param modelName model name
     * @param row       data row to be created
     * @return id
     */
    @PostMapping(value = "/createOne")
    @Operation(description = "Create one row and return the id.")
    @DataMask
    public ApiResponse<K> createOne(@PathVariable String modelName,
                                    @RequestBody Map<String, Object> row) {
        return ApiResponse.success(modelService.createOne(modelName, row));
    }

    /**
     * Create a single row, fetch the row data after creation.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     *
     * @param modelName model name
     * @param row       data row to be created
     * @return row data with id and other latest field values
     */
    @PostMapping(value = "/createOneAndFetch")
    @Operation(description = "Create one row and fetch the latest values from database.")
    @DataMask
    public ApiResponse<Map<String, Object>> createOneAndFetch(@PathVariable String modelName,
                                                              @RequestBody Map<String, Object> row) {
        return ApiResponse.success(modelService.createOneAndFetch(modelName, row, ConvertType.REFERENCE));
    }

    /**
     * Create multiple rows and return the IDs.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     *
     * @param modelName model name
     * @param rows      data rows to be created
     * @return id list
     */
    @PostMapping("/createList")
    @Operation(description = "Create multiple rows and return the ID list.")
    public ApiResponse<List<K>> createList(@PathVariable String modelName,
                                           @RequestBody List<Map<String, Object>> rows) {
        this.validateBatchSize(rows.size());
        return ApiResponse.success(modelService.createList(modelName, rows));
    }

    /**
     * Create multiple rows, fetch the rows after creation.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     *
     * @param modelName model name
     * @param rows      data rows to be created
     * @return row data list with id and other latest field values
     */
    @PostMapping("/createListAndFetch")
    @Operation(description = "Create multiple rows and fetch the latest values from database.")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> createListAndFetch(@PathVariable String modelName,
                                                                     @RequestBody List<Map<String, Object>> rows) {
        this.validateBatchSize(rows.size());
        return ApiResponse.success(modelService.createListAndFetch(modelName, rows, ConvertType.REFERENCE));
    }

    /**
     * Get one row by id.
     * If the fields are not specified, all accessible fields as the default.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     *
     * @param modelName     model name
     * @param getByIdParams Get one row by ID parameters
     * @return data row
     */
    @PostMapping(value = "/getById")
    @Operation(description = "Get one row by ID.")
    @DataMask
    public ApiResponse<Map<String, Object>> getById(@PathVariable String modelName,
                                                    @RequestBody GetByIdParams getByIdParams) {
        ContextHolder.getContext().setEffectiveDate(getByIdParams.getEffectiveDate());
        Assert.notNull(getByIdParams.getId(), "The ID of the data to be read cannot be null!");
        K id = IdUtils.formatId(modelName, getByIdParams.getId());
        SubQueries subQueries = new SubQueries();
        if (!CollectionUtils.isEmpty(getByIdParams.getSubQueries())) {
            subQueries.setQueryMap(getByIdParams.getSubQueries());
        }
        Optional<Map<String, Object>> row = modelService.getById(modelName, id,
                getByIdParams.getFields(), subQueries, ConvertType.REFERENCE);
        return ApiResponse.success(row.orElse(null));
    }

    /**
     * Get multiple rows by ids.
     * If the fields is not specified, all accessible fields as the default.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     *
     * @param modelName     model name
     * @param getByIdsParams Get multiple rows by IDs parameters
     * @return List<Map> of multiple data
     */
    @PostMapping(value = "/getByIds")
    @Operation(description = "Get multiple rows by IDs.")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> getByIds(@PathVariable String modelName,
                                                           @RequestBody GetByIdsParams getByIdsParams) {
        ContextHolder.getContext().setEffectiveDate(getByIdsParams.getEffectiveDate());
        List<K> ids = IdUtils.formatIds(modelName, getByIdsParams.getIds());
        this.validateIds(ids);
        SubQueries subQueries = new SubQueries();
        if (!CollectionUtils.isEmpty(getByIdsParams.getSubQueries())) {
            subQueries.setQueryMap(getByIdsParams.getSubQueries());
        }
        return ApiResponse.success(modelService.getByIds(modelName, ids,
                getByIdsParams.getFields(), subQueries, ConvertType.REFERENCE));
    }

    /**
     * What the caller may do with each record: the sensitive field sets hidden or read-only on it, and
     * the actions available on it — so a form can show the sections and buttons that will work.
     *
     * @param modelName      model name
     * @param getByIdsParams the record ids; other parameters are ignored
     * @return one entry per id
     */
    @PostMapping(value = "/getRecordAccess")
    @Operation(description = "The caller's access to each record: hidden / read-only sensitive sets and available actions.")
    public ApiResponse<List<RecordAccess>> getRecordAccess(@PathVariable String modelName,
                                                           @RequestBody GetByIdsParams getByIdsParams) {
        List<K> ids = IdUtils.formatIds(modelName, getByIdsParams.getIds());
        this.validateIds(ids);
        return ApiResponse.success(permissionService.getRecordAccess(modelName, ids));
    }

    /**
     * Which sensitive field sets a form creating a record of this model must not show.
     *
     * @param modelName model name
     * @return the hidden sets
     */
    @GetMapping("/getCreateAccess")
    @Operation(description = "Sensitive field sets a create form must not show.")
    public ApiResponse<CreateAccess> getCreateAccess(@PathVariable String modelName) {
        return ApiResponse.success(permissionService.getCreateAccess(modelName));
    }

    /**
     * Get the copyable fields value by ID, no data inserted.
     *
     * @param modelName model name
     * @param id        source data id
     * @return map of copyable field values
     */
    @GetMapping("/getCopyableFields")
    @Operation(description = "Get the copyable fields value by ID, no data inserted.")
    @Parameter(name = "id", description = "Data ID to be copied.", schema = @Schema(type = "string"))
    @DataMask
    public ApiResponse<Map<String, Object>> getCopyableFields(@PathVariable String modelName, @RequestParam K id) {
        id = IdUtils.formatId(modelName, id);
        return ApiResponse.success(modelService.getCopyableFields(modelName, id));
    }

    /**
     * Get the default values of the model fields.
     *
     * @param modelName model name
     * @return default values of the model fields
     */
    @GetMapping("/getDefaultValues")
    @Operation(summary = "getDefaultValues", description = "Get the default values of the model fields.")
    public ApiResponse<Map<String, Object>> getDefaultValues(@PathVariable String modelName) {
        Assert.notBlank(modelName, "Model name cannot be empty.");
        List<MetaField> metaFields = ModelManager.getModelFields(modelName);
        Map<String, Object> defaultValues = new HashMap<>();
        for (MetaField metaField : metaFields) {
            if (metaField.getDefaultValueObject() != null) {
                defaultValues.put(metaField.getFieldName(), metaField.getDefaultValue());
            }
        }
        return ApiResponse.success(defaultValues);
    }

    /**
     * Get the original value for masking field based on the id and field name.
     *
     * @param modelName model name
     * @param id        data id
     * @param field     masking field name
     * @return unmasked field value
     */
    @GetMapping("/getUnmaskedField")
    @Operation(description = "Get the original value for masking field.")
    @Parameters({
            @Parameter(name = "id", description = "Data ID to be read", schema = @Schema(type = "string")),
            @Parameter(name = "field", description = "The masking field name to get the original value"),
            @Parameter(name = "effectiveDate", description = "Effective date for timeline model.")
    })
    public ApiResponse<String> getUnmaskedField(@PathVariable String modelName,
                                                @RequestParam K id,
                                                @RequestParam String field,
                                                @RequestParam(required = false) LocalDate effectiveDate) {
        ContextHolder.getContext().setEffectiveDate(effectiveDate);
        id = IdUtils.formatId(modelName, id);
        return ApiResponse.success(modelService.getUnmaskedField(modelName, id, field));
    }

    /**
     * Get the original values for masking fields based on the id and field names.
     *
     * @param modelName model name
     * @param id        data id
     * @param fields    masking field names
     * @return unmasked field values map, {fieldName: fieldValue}
     */
    @GetMapping("/getUnmaskedFields")
    @Operation(description = "Get the original values for multiple masking fields.")
    @Parameters({
            @Parameter(name = "id", description = "Data ID to be read", schema = @Schema(type = "string")),
            @Parameter(name = "fields", description = "The masking field list to get the original values"),
            @Parameter(name = "effectiveDate", description = "Effective date for timeline model.")
    })
    public ApiResponse<Map<String, Object>> getUnmaskedFields(@PathVariable String modelName,
                                                              @RequestParam K id,
                                                              @RequestParam List<String> fields,
                                                              @RequestParam(required = false) LocalDate effectiveDate) {
        ContextHolder.getContext().setEffectiveDate(effectiveDate);
        id = IdUtils.formatId(modelName, id);
        return ApiResponse.success(modelService.getUnmaskedFields(modelName, id, fields));
    }

    /**
     * Get the original values of masked fields on a record this one owns: the one-to-one section {@code field}
     * points at, or, for a one-to-many {@code field}, its row {@code rowId}. A record an owner holds through such
     * a field is read through the owner and has no endpoints of its own, so its masked values are asked for here.
     * The owner has to be readable, the row has to be the owner's, and the section's own row and field rules
     * still decide what comes back.
     *
     * @param modelName the owner model
     * @param id        the owner's id
     * @param field     the owner's one-to-one or one-to-many field
     * @param rowId     the row of a one-to-many field; ignored for a one-to-one field
     * @param fields    masked fields of the owned model
     * @return unmasked field values map, {fieldName: fieldValue}
     */
    @GetMapping("/getRelationUnmaskedFields")
    @Operation(description = "Get the original values of masked fields on a record this one owns.")
    @Parameters({
            @Parameter(name = "id", description = "The owner's ID", schema = @Schema(type = "string")),
            @Parameter(name = "field", description = "The owner's one-to-one or one-to-many field"),
            @Parameter(name = "rowId", description = "The row of a one-to-many field", schema = @Schema(type = "string")),
            @Parameter(name = "fields", description = "Masked fields of the owned model"),
            @Parameter(name = "effectiveDate", description = "Effective date for timeline model.")
    })
    public ApiResponse<Map<String, Object>> getRelationUnmaskedFields(@PathVariable String modelName,
                                                                      @RequestParam K id,
                                                                      @RequestParam String field,
                                                                      @RequestParam(required = false) String rowId,
                                                                      @RequestParam List<String> fields,
                                                                      @RequestParam(required = false) LocalDate effectiveDate) {
        ContextHolder.getContext().setEffectiveDate(effectiveDate);
        MetaField relation = ownedRelation(modelName, field);
        K ownerId = IdUtils.formatId(modelName, id);
        String ownedModel = relation.getRelatedModel();
        K ownedId;
        if (relation.getFieldType() == FieldType.ONE_TO_ONE) {
            Object value = modelService.getById(modelName, ownerId, List.of(field))
                    .map(row -> row.get(field))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The data of model {0} with id {1} does not exist!", modelName, ownerId));
            Object referenced = value instanceof Map<?, ?> reference ? reference.get(ModelConstant.ID) : value;
            Assert.notNull(referenced, "The {0} of model {1} with id {2} is empty.", field, modelName, ownerId);
            ownedId = IdUtils.formatId(ownedModel, (Serializable) referenced);
        } else {
            Assert.notBlank(rowId, "A one-to-many field needs the rowId of its row.");
            requireReadable(modelName, ownerId);
            ownedId = IdUtils.formatId(ownedModel, rowId);
            Filters ownRow = new Filters().eq(ModelConstant.ID, ownedId).eq(relation.getRelatedField(), ownerId);
            Assert.isTrue(modelService.count(ownedModel, ownRow) > 0,
                    "Row {0} of {1} does not belong to model {2} with id {3}.", rowId, field, modelName, ownerId);
        }
        return ApiResponse.success(modelService.getUnmaskedFields(ownedModel, ownedId, fields));
    }

    /**
     * Page through the rows of a one-to-many field of one record. The rows a record owns are read through it
     * and have no endpoints of their own: the owner has to be readable, and the rows' own rules still bound the
     * page. The query parameters apply to the owned model; the rows are limited to this record's.
     *
     * @param modelName   the owner model
     * @param id          the owner's id
     * @param field       the owner's one-to-many field
     * @param queryParams fields, filters, orders and paging on the owned model
     * @return the record's rows in the page
     */
    @PostMapping(value = "/searchRelationPage")
    @Operation(description = "Page through the rows of a one-to-many field of one record.")
    @Parameters({
            @Parameter(name = "id", description = "The owner's ID", schema = @Schema(type = "string")),
            @Parameter(name = "field", description = "The owner's one-to-many field")
    })
    @DataMask
    public ApiResponse<Page<Map<String, Object>>> searchRelationPage(@PathVariable String modelName,
                                                                     @RequestParam K id,
                                                                     @RequestParam String field,
                                                                     @RequestBody(required = false) QueryParams queryParams) {
        MetaField relation = ownedRelation(modelName, field);
        Assert.isTrue(relation.getFieldType() == FieldType.ONE_TO_MANY,
                "Field {0} of model {1} is not a one-to-many field.", field, modelName);
        id = IdUtils.formatId(modelName, id);
        requireReadable(modelName, id);
        if (queryParams == null) {
            queryParams = new QueryParams();
        }
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(queryParams);
        flexQuery.setFilters(Filters.and(flexQuery.getFilters(), new Filters().eq(relation.getRelatedField(), id)));
        Page<Map<String, Object>> page = Page.of(queryParams.getPageNumber(), queryParams.getPageSize());
        return ApiResponse.success(modelService.searchPage(relation.getRelatedModel(), flexQuery, page));
    }

    /** A one-to-one or keyed one-to-many field of {@code modelName}, the kind through which it owns records. */
    private static MetaField ownedRelation(String modelName, String field) {
        MetaField relation = ModelManager.getModelField(modelName, field);
        boolean toMany = relation.getFieldType() == FieldType.ONE_TO_MANY
                && relation.getRelatedField() != null && !relation.getRelatedField().isBlank();
        Assert.isTrue(toMany || relation.getFieldType() == FieldType.ONE_TO_ONE,
                "Field {0} of model {1} is not a one-to-one or one-to-many field.", field, modelName);
        return relation;
    }

    /** Refuses unless the caller can read the record: what it owns is read through it. */
    private void requireReadable(String modelName, K id) {
        if (modelService.getById(modelName, id, List.of(ModelConstant.ID)).isEmpty()) {
            throw new IllegalArgumentException("The data of model {0} with id {1} does not exist!", modelName, id);
        }
    }

    /**
     * Update one row by id.
     *
     * @param modelName model name
     * @param row       data row to be updated
     * @return true / Exception
     */
    @PostMapping(value = "/updateOne")
    @Operation(description = "Update one row by ID. Return true on success.")
    @DataMask
    public ApiResponse<Boolean> updateOne(@PathVariable String modelName,
                                          @RequestBody Map<String, Object> row) {
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        IdUtils.formatMapId(modelName, row);
        return ApiResponse.success(modelService.updateOne(modelName, row));
    }

    /**
     * Update one row by id, and fetch the latest field values.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     *
     * @param modelName model name
     * @param row       data row to be updated
     * @return row map fetched from the database after updating
     */
    @PostMapping(value = "/updateOneAndFetch")
    @Operation(description = "Update one row by ID, and fetch the latest values from database.")
    @DataMask
    public ApiResponse<Map<String, Object>> updateOneAndFetch(@PathVariable String modelName,
                                                               @RequestBody Map<String, Object> row) {
        Assert.notEmpty(row, "The data to be updated cannot be empty!");
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        IdUtils.formatMapId(modelName, row);
        return ApiResponse.success(modelService.updateOneAndFetch(modelName, row, ConvertType.REFERENCE));
    }

    /**
     * Update multiple rows by ids. Each row in the list can have different fields.
     *
     * @param modelName model name
     * @param rows      data rows to be updated
     * @return true / Exception
     */
    @PostMapping(value = "/updateList")
    @Operation(description = "Update multiple rows by ID. Return true on success.")
    public ApiResponse<Boolean> updateList(@PathVariable String modelName,
                                           @RequestBody List<Map<String, Object>> rows) {
        Assert.notEmpty(rows, "The data to be updated cannot be empty!");
        this.validateBatchSize(rows.size());
        IdUtils.formatMapIds(modelName, rows);
        return ApiResponse.success(modelService.updateList(modelName, rows));
    }

    /**
     * Update multiple rows by ids. Each row in the list can have different fields.
     * And fetch the latest field values.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     *
     * @param modelName model name
     * @param rows      data rows to be updated
     * @return rows fetched from the database after updating
     */
    @PostMapping(value = "/updateListAndFetch")
    @Operation(description = "Update multiple rows by ID, and fetch the latest values from database.")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> updateListAndFetch(@PathVariable String modelName,
                                                                     @RequestBody List<Map<String, Object>> rows) {
        Assert.notEmpty(rows, "The data to be updated cannot be empty!");
        this.validateBatchSize(rows.size());
        IdUtils.formatMapIds(modelName, rows);
        return ApiResponse.success(modelService.updateListAndFetch(modelName, rows, ConvertType.REFERENCE));
    }

    /**
     * Bulk update data based on the filters, according to the specified field values map.
     *
     * @param modelName model name
     * @param bulkUpdateParams bulk update parameters
     * @return number of affected rows
     */
    @PostMapping(value = "/updateByFilter")
    @Operation(description = "Batch update data according to the filters, within the current user's permission scope.")
    public ApiResponse<Integer> updateByFilter(@PathVariable String modelName,
                                               @RequestBody BulkUpdateParams bulkUpdateParams) {
        Map<String, Object> values = bulkUpdateParams.getValues();
        Assert.notEmpty(values, "The updated data cannot be empty!");
        ContextHolder.getContext().setEffectiveDate(bulkUpdateParams.getEffectiveDate());
        Integer count = modelService.updateByFilter(modelName, bulkUpdateParams.getFilters(), values);
        return ApiResponse.success(count);
    }

    /**
     * Delete one row by id.
     * All slices related to this `id` will be deleted if the model is a timeline model.
     *
     * @param modelName model name
     * @param id        data id
     * @return true / Exception
     */
    @PostMapping(value = "/deleteById")
    @Operation(description = "Delete one row by ID. All slices related to this `ID` will be deleted for timeline model.")
    @Parameter(name = "id", description = "ID of the data to be deleted.", schema = @Schema(type = "string"))
    public ApiResponse<Boolean> deleteById(@PathVariable String modelName, @RequestParam K id) {
        id = IdUtils.formatId(modelName, id);
        return ApiResponse.success(modelService.deleteById(modelName, id));
    }

    /**
     * Delete multiple rows by ids.
     *
     * @param modelName model name
     * @param ids       data ids
     * @return True / Exception
     */
    @PostMapping(value = "/deleteByIds")
    @Operation(description = "Delete multiple rows by IDs.")
    @Parameter(name = "ids", description = "IDs of the data to be deleted.")
    public ApiResponse<Boolean> deleteByIds(@PathVariable String modelName,
                                            @RequestParam List<K> ids) {
        this.validateIds(ids);
        ids = IdUtils.formatIds(modelName, ids);
        return ApiResponse.success(modelService.deleteByIds(modelName, ids));
    }

    /**
     * Copy a single row based on id, and return the id of the new row.
     *
     * @param modelName model name
     * @param id        data source ID to be copied.
     * @return id of the new data
     */
    @PostMapping(value = "/copyById")
    @Operation(description = "Copy one row based on ID, and return the ID of the new row.")
    @Parameter(name = "id", description = "Data ID to be copied.", schema = @Schema(type = "string"))
    @DataMask
    public ApiResponse<K> copyById(@PathVariable String modelName, @RequestParam K id) {
        id = IdUtils.formatId(modelName, id);
        return ApiResponse.success(modelService.copyById(modelName, id));
    }

    /**
     * Copy a single row based on id, and fetch the new row.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     *
     * @param modelName model name
     * @param id        data source id
     * @return new row data
     */
    @PostMapping(value = "/copyByIdAndFetch")
    @Operation(description = "Copy one row based on ID, and fetch the new row.")
    @Parameter(name = "id", description = "Data ID to be copied.", schema = @Schema(type = "string"))
    @DataMask
    public ApiResponse<Map<String, Object>> copyByIdAndFetch(@PathVariable String modelName, @RequestParam K id) {
        id = IdUtils.formatId(modelName, id);
        return ApiResponse.success(modelService.copyByIdAndFetch(modelName, id, ConvertType.REFERENCE));
    }

    /**
     * Copy multiple rows based on ids, and return the ids of the new rows.
     *
     * @param modelName model name
     * @param ids       data source ids
     * @return ids of the new data
     */
    @PostMapping(value = "/copyByIds")
    @Operation(description = "Copy multiple rows based on IDs, and return the new data IDs.")
    @Parameter(name = "ids", description = "Data IDs to be copied.")
    @DataMask
    public ApiResponse<List<K>> copyByIds(@PathVariable String modelName, @RequestParam List<K> ids) {
        this.validateIds(ids);
        ids = IdUtils.formatIds(modelName, ids);
        return ApiResponse.success(modelService.copyByIds(modelName, ids));
    }

    /**
     * Copy multiple rows based on ids, and fetch the new rows.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     *
     * @param modelName model name
     * @param ids       source data ids
     * @return new rows data
     */
    @PostMapping(value = "/copyByIdsAndFetch")
    @Operation(description = "Copy multiple rows based on ids, and fetch the new rows.")
    @Parameter(name = "ids", description = "Data IDs to be copied.")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> copyByIdsAndFetch(@PathVariable String modelName,
                                                                    @RequestParam List<K> ids) {
        this.validateIds(ids);
        ids = IdUtils.formatIds(modelName, ids);
        return ApiResponse.success(modelService.copyByIdsAndFetch(modelName, ids, ConvertType.REFERENCE));
    }

    /**
     * Query data with pagination. The page size cannot exceed the MAX_BATCH_SIZE.
     * Set `convertType = REFERENCE` to get the reference object of expandable fields.
     * Support SUM, AVG, MIN, MAX, COUNT aggregation queries.
     *
     * @param modelName model name
     * @param queryParams  aggregation query parameters
     * @return data list in the page
     */
    @PostMapping(value = "/searchPage")
    @Operation(description = "Paging aggregation query parameters, including fields, filters, orders, pageNumber, pageSize" +
                    "groupBy, aggFunctions, subQueries, etc. Use the backend default value when not specified.")
    @DataMask
    public ApiResponse<Page<Map<String, Object>>> searchPage(@PathVariable String modelName,
                                                             @RequestBody(required = false) QueryParams queryParams) {
        if (queryParams == null) {
            queryParams = new QueryParams();
        }
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(queryParams);
        Page<Map<String, Object>> page = Page.of(queryParams.getPageNumber(), queryParams.getPageSize());
        return ApiResponse.success(modelService.searchPage(modelName, flexQuery, page));
    }

    /**
     * Query data list without pagination, the `limit` is default to DEFAULT_PAGE_SIZE.
     * The OneToOne/ManyToOne field value is the `displayName` of the related model.
     * Support SUM, AVG, MIN, MAX, COUNT aggregation queries.
     *
     * @param modelName model name
     * @param searchListParams  Aggregation query parameter
     * @return data list
     */
    @PostMapping(value = "/searchList")
    @Operation(description = "Query the data list based on the specified fields, filters, orders, limitSize, " +
            "aggFunctions, and subQueries. Default limit to 50.")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> searchList(@PathVariable String modelName,
                                                             @RequestBody(required = false) SearchListParams searchListParams) {
        FlexQuery flexQuery = SearchListParams.convertParamsToFlexQuery(searchListParams);
        SourceRecord source = searchListParams == null ? null : searchListParams.getSourceRecord();
        narrowToWritableCompanies(modelName, source, flexQuery);
        narrowToWritableDepartments(modelName, source, flexQuery);
        return ApiResponse.success(modelService.searchList(modelName, flexQuery));
    }

    /**
     * One page of displayNames, matched on the match field within the filters, in a stable order.
     * Ten candidates a page unless {@code pageSize} says otherwise; {@code pageNumber} asks for the next ones.
     *
     * @param modelName model name
     * @param searchNameParams SearchNameParams
     * @return the page's rows, each with displayName and the additional fields
     */
    @PostMapping(value = "/searchName")
    @Operation(description = "Query the displayNames based on the match field, filters and orders, one page at a "
            + "time: pageNumber (from 1) and pageSize (default 10).")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> searchName(@PathVariable String modelName,
                                                             @RequestBody(required = false) SearchNameParams searchNameParams) {
        SearchNameParams params = searchNameParams == null ? new SearchNameParams() : searchNameParams;
        FlexQuery flexQuery = params.toFlexQuery();
        SourceRecord source = searchNameParams == null ? null : searchNameParams.getSourceRecord();
        narrowToWritableCompanies(modelName, source, flexQuery);
        narrowToWritableDepartments(modelName, source, flexQuery);
        return ApiResponse.success(modelService.searchName(modelName, flexQuery, params.toPage()));
    }

    /** ANDs {@link #writableCompanies} onto a read of the options, when it narrows them. */
    private void narrowToWritableCompanies(String modelName, SourceRecord source, FlexQuery flexQuery) {
        Set<Long> writable = writableCompanies(modelName, source, permissionService);
        if (writable != null) {
            narrowTo(new Filters().in(ModelConstant.ID, writable), source, flexQuery);
        }
    }

    /** ANDs {@link #writableDepartments} onto a read of the options, when it narrows them. */
    private void narrowToWritableDepartments(String modelName, SourceRecord source, FlexQuery flexQuery) {
        Filters writable = writableDepartments(modelName, source, permissionService);
        if (writable != null) {
            narrowTo(writable, source, flexQuery);
        }
    }

    /**
     * ANDs {@code writable} onto the options, the value the edited record holds now kept among them.
     *
     * <p>A record may already name a company or department the write could not choose — set by an administrator or by
     * the system — and its save keeps it while the field is left alone. Narrowed without it, the picker would show a
     * value it does not offer, as if the record were broken.
     */
    private void narrowTo(Filters writable, SourceRecord source, FlexQuery flexQuery) {
        Object current = currentValue(source);
        Filters offered = current == null ? writable
                : Filters.or(writable, Filters.of(ModelConstant.ID, Operator.EQUAL, current));
        flexQuery.setFilters(Filters.and(flexQuery.getFilters(), offered));
    }

    /**
     * The id the edited record's picked field holds now, read as the caller reads the record; null for a new record, a
     * search naming no field, an empty field, or a record the caller cannot read.
     */
    private Object currentValue(SourceRecord source) {
        if (source == null || source.getFieldName() == null || source.getFieldName().isBlank()
                || source.getRecordId() == null || String.valueOf(source.getRecordId()).isBlank()) {
            return null;
        }
        String field = source.getFieldName();
        K id = IdUtils.formatId(source.getModel(), source.getRecordId());
        Object value = modelService.getById(source.getModel(), id, List.of(field))
                .map(row -> row.get(field)).orElse(null);
        Object referenced = value instanceof Map<?, ?> reference ? reference.get(ModelConstant.ID) : value;
        return referenced == null || String.valueOf(referenced).isBlank() ? null : referenced;
    }

    /**
     * The departments a picker may offer when it places a record being written; null when nothing narrows it.
     *
     * <p>The same reasoning as {@link #writableCompanies}, for departments: the department list as the user
     * reads it answers for what they may view, and an exemption from the department grant shows them all of it,
     * so a form offered departments the save would then refuse. Its read says which record it is for, and the
     * department grants of the roles holding that write answer instead — create when the record has no id yet,
     * update when it has, then by the roles reaching that record
     * ({@link PermissionService#getWritableDepartmentScope(String, AccessType, java.io.Serializable)}), as the
     * save judges it. A picker that names its field,
     * and the field does not place the record ({@link PermissionService#placesInDepartment}), reads as any other.
     */
    static Filters writableDepartments(String modelName, SourceRecord source, PermissionService permissionService) {
        if (!ModelConstant.DEPARTMENT_MODEL.equals(modelName) || source == null
                || source.getModel() == null || source.getModel().isBlank()
                || !ModelManager.existModel(source.getModel())) {
            return null;
        }
        if (source.getFieldName() != null && !source.getFieldName().isBlank()
                && !PermissionService.placesInDepartment(source.getModel(), source.getFieldName())) {
            return null;
        }
        boolean creating = source.getRecordId() == null || String.valueOf(source.getRecordId()).isBlank();
        return creating
                ? permissionService.getWritableDepartmentScope(source.getModel(), AccessType.CREATE)
                : permissionService.getWritableDepartmentScope(source.getModel(), AccessType.UPDATE,
                        source.getRecordId());
    }

    /**
     * The companies a picker may offer when it names a company on a record being written; null when
     * nothing narrows it.
     *
     * <p>The company list as the user reads it reaches the companies of every role they hold, and a role
     * with no company grant — the self-service role everyone carries — reaches them all. A form picking
     * a company for a record (an employee's signing entity, a cost centre's company) offered companies
     * the write would then refuse. Its search says which record it is for ({@link SourceRecord}): the
     * company grants of the roles holding that write answer instead — create when the record has no id
     * yet, update when it has, then by the roles reaching that record
     * ({@link PermissionService#getGrantedCompanyIds(String, AccessType, java.io.Serializable)}).
     *
     * <p>A write granted no company offers none: every company the picker could list would be refused. The
     * name search and the list read a tree or list picker loads through answer alike. A picker that names its field,
     * and the field names no company the save judges ({@link PermissionService#namesCompany}) — one not stored, say,
     * that only filters another picker — reads as any other.
     */
    static Set<Long> writableCompanies(String modelName, SearchNameParams params, PermissionService permissionService) {
        return writableCompanies(modelName, params == null ? null : params.getSourceRecord(), permissionService);
    }

    /** {@link #writableCompanies(String, SearchNameParams, PermissionService)} for the record a read is for. */
    static Set<Long> writableCompanies(String modelName, SourceRecord source, PermissionService permissionService) {
        if (!ModelConstant.COMPANY_MODEL.equals(modelName) || source == null) {
            return null;
        }
        if (source.getModel() == null || source.getModel().isBlank() || !ModelManager.existModel(source.getModel())) {
            return null;
        }
        if (source.getFieldName() != null && !source.getFieldName().isBlank()
                && !PermissionService.namesCompany(source.getModel(), source.getFieldName())) {
            return null;
        }
        boolean creating = source.getRecordId() == null || String.valueOf(source.getRecordId()).isBlank();
        return creating
                ? permissionService.getGrantedCompanyIds(source.getModel(), AccessType.CREATE)
                : permissionService.getGrantedCompanyIds(source.getModel(), AccessType.UPDATE, source.getRecordId());
    }

    /**
     * Simple aggregation query params by `filters` and `aggFunctions`.
     * SUM, AVG, MIN, MAX, COUNT aggregation queries, such as `["SUM", "amount"]`, or `[["SUM", "amount"], [], ...]`.
     * Each key of the result is a camel case string concatenated with the functionName + fieldName, e.g., `sumAmount`.
     * Use the searchPage interface when grouping or paging is needed.
     *
     * @param modelName model name
     * @param simpleAggParams Simple aggregation query parameters
     * @return Result map.
     */
    @PostMapping(value = "/searchSimpleAgg")
    @Operation(description = """
            Simple aggregation query params. Pure SUM, AVG, MIN, MAX, COUNT aggregate query, like `["SUM", "amount"]`
            or `[["SUM", "amount"], [], ...]`, the return key is `sumAmount`.""")
    @DataMask
    public ApiResponse<Map<String, Object>> searchSimpleAgg(@PathVariable String modelName,
                                                            @RequestBody SimpleAggParams simpleAggParams) {
        ContextHolder.getContext().setEffectiveDate(simpleAggParams.getEffectiveDate());
        FlexQuery flexQuery = new FlexQuery(simpleAggParams.getFilters());
        Assert.notTrue(AggFunctions.isEmpty(simpleAggParams.getAggFunctions()), "`aggFunctions` cannot be null!");
        // Set AggFunction parameters
        flexQuery.setAggFunctions(simpleAggParams.getAggFunctions());
        return ApiResponse.success(modelService.searchOne(modelName, flexQuery).orElse(null));
    }

    /**
     * Query the PivotTable; without pagination, the data is limited to no more than DEFAULT_BATCH_SIZE records.
     *
     * @param modelName model name
     * @param queryParams  aggregation query parameters
     * @return PivotTable object
     */
    @PostMapping(value = "/searchPivot")
    @Operation(description = "Get the pivot table data based on the specified fields, filters, orders, groupBy, splitBy.")
    @DataMask
    public ApiResponse<PivotTable> searchPivot(@PathVariable String modelName,
                                               @RequestBody(required = false) QueryParams queryParams) {
        if (queryParams == null) {
            queryParams = new QueryParams();
        }
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(queryParams);
        flexQuery.setSplitBy(queryParams.getSplitBy());
        return ApiResponse.success(modelService.searchPivot(modelName, flexQuery));
    }

    /**
     * Count a query. Support group counting by `groupBy` parameter, count the number of each group.
     * That is, the amount of data under each grouped field value. For example,
     * `groupBy=name,code,sequence & orders=sequence`, the actual SQL statement is:
     * `SELECT name, code, sequence, count(*) AS count FROM table_name GROUP BY name, code, sequence ORDER BY sequence`
     *
     * @param modelName model name
     * @param countParams CountParams
     * @return Group counting results.
     */
    @PostMapping(value = "/count")
    @Operation(description = "Returns a count or group counting based on the specified `filter`, `groupBy`, and `orders`.")
    @DataMask
    public ApiResponse<CountResult> count(@PathVariable String modelName,
                                          @RequestBody(required = false) CountParams countParams) {
        if (countParams == null) {
            countParams = new CountParams();
        }
        ContextHolder.getContext().setEffectiveDate(countParams.getEffectiveDate());
        CountResult result = new CountResult();
        List<String> groupBy = countParams.getGroupBy();
        if (!CollectionUtils.isEmpty(groupBy)) {
            Assert.allNotBlank(groupBy, "`groupBy` cannot contain empty value: {0}", groupBy);
            FlexQuery flexQuery = new FlexQuery(countParams.getFilters(), countParams.getOrders());
            flexQuery.setFields(new HashSet<>(groupBy));
            flexQuery.setGroupBy(groupBy);
            flexQuery.setConvertType(ConvertType.TYPE_CAST);
            result.setGroups(modelService.searchList(modelName, flexQuery));
        } else {
            result.setTotal(modelService.count(modelName, countParams.getFilters()));
        }
        return ApiResponse.success(result);
    }


    /**
     * Compute the server-side linkage of a field change. The request carries the new value of
     * the changed field plus the current values of the companion fields the client declared;
     * the response carries a value patch and the complete readonly / required field-name lists
     * to apply. Dispatched to the {@link FieldOnChangeHandler} registered for the
     * (model, field) pair.
     *
     * @param modelName model name
     * @param fieldName changed field name
     * @param onChangeParams OnChangeParams
     * @return OnChangeResponse
     */
    @PostMapping(value = "/onChange/{fieldName}")
    @Operation(description = "Computes the linkage of a field change: a value patch plus the complete readonly / required field-name lists.")
    @DataMask
    public ApiResponse<OnChangeResponse> onChange(@PathVariable String modelName,
                                                  @PathVariable String fieldName,
                                                  @RequestBody OnChangeParams onChangeParams) {
        return ApiResponse.success(fieldOnChangeRegistry.dispatch(modelName, fieldName, onChangeParams));
    }
}
