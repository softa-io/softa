package io.softa.starter.es.service.impl;

import java.io.Serializable;
import java.util.*;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.jdbc.pipeline.DataPipelineProxy;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.es.document.ChangeLogDocument;
import io.softa.starter.es.service.ChangeLogService;

import static io.softa.framework.orm.constant.ModelConstant.SLICE_ID;
import static io.softa.framework.orm.enums.AccessType.*;

/**
 * ChangeLog Service Implementation
 */
@Service
public class ChangeLogServiceImpl extends ESServiceImpl<ChangeLog> implements ChangeLogService {

    @Lazy
    @Autowired
    private PermissionService permissionService;

    @Lazy
    @Autowired
    private ModelService<Serializable> modelService;

    @Autowired
    private DataPipelineProxy dataPipelineProxy;

    @Value("${spring.elasticsearch.index.changelog}")
    private String changeLogIndexName;

    /** ES service implementation class must specify the index name by implementing this method */
    public String getIndexName() {
        return changeLogIndexName;
    }

    /**
     * ChangeLog is persisted in ES as {@link ChangeLogDocument} (the two payload
     * maps are flattened to JSON strings on the wire), so query through the
     * document class and translate the page back before returning.
     */
    @Override
    public Page<ChangeLog> searchPage(Filters filters, Orders orders, Page<ChangeLog> page) {
        Page<ChangeLogDocument> docPage = new Page<>(
                page.getPageNumber(), page.getPageSize(), page.isCursorPage(), page.isCount());
        super.searchPage(ChangeLogDocument.class, filters, orders, docPage);
        page.setTotalCount(docPage.getTotalCount());
        List<ChangeLog> logs = docPage.getRows().stream().map(ChangeLogDocument::toChangeLog).toList();
        page.setRows(visibleToReader(logs));
        return page;
    }

    /**
     * The logs as the reader may see them — what every read of the log returns. Per record first,
     * then the fields hidden on every record: an update left with nothing the reader may see is then
     * dropped whole by {@link #withoutBlockedFields}.
     */
    private List<ChangeLog> visibleToReader(List<ChangeLog> logs) {
        maskInaccessibleFields(logs);
        return withoutBlockedFields(logs);
    }

    /**
     * One part of a record's history: the logs of one model, for some rows and/or the rows that
     * point back at the record.
     *
     * @param rowIds rows asked for by id — the record, a one-to-one row, the current child rows
     * @param ref {@code field=id} a child row's log carries when it points at the record; null when
     *            the part is not a one-to-many
     * @param visibleFields every stored field the reader may see, when their sensitive field sets
     *                      hide some; null when nothing is hidden
     */
    record HistoryPart(String model, List<String> rowIds, String ref, List<String> visibleFields) {}

    /**
     * Beyond this many current rows a one-to-many is asked by reference alone.
     *
     * <p>The current rows' ids are there for logs written before logs carried references. A
     * relation that grows without bound — a ledger of balance events — would turn that into a
     * clause of thousands of ids on every read, for history that predates references; past this
     * size the reference stands alone, and such a relation's pre-reference history is not listed.
     */
    static final int CURRENT_ROWS_ASKED_BY_ID = 200;

    @Override
    public Page<ChangeLog> getRecordChangeLog(String modelName, Serializable id, List<String> relations,
                                              Page<ChangeLog> page, String order, boolean includeCreation) {
        permissionService.checkIdsFieldsAccess(modelName, Collections.singletonList(id), null, READ);
        List<HistoryPart> parts = this.historyParts(modelName, id, relations == null ? List.of() : relations);

        NativeQuery query = NativeQuery.builder()
                .withQuery(this.historyQuery(parts, includeCreation))
                .withPageable(PageRequest.of(page.getPageNumber() - 1, page.getPageSize()))
                .withSort(Sort.by(Orders.ASC.equals(order) ? Sort.Direction.ASC : Sort.Direction.DESC,
                        "changedTime"))
                .withTrackTotalHits(true)
                .build();
        SearchHits<ChangeLogDocument> hits =
                esOperations.search(query, ChangeLogDocument.class, IndexCoordinates.of(getIndexName()));
        page.setTotalCount(hits.getTotalHits());
        page.setRows(visibleToReader(hits.getSearchHits().stream()
                .map(SearchHit::getContent).map(ChangeLogDocument::toChangeLog).toList()));
        return this.processChangeLogData(modelName, page, ConvertType.REFERENCE);
    }

    /**
     * Which logs make up a record's history: the record's own, and those of the rows its named
     * relations hold.
     *
     * <p>A one-to-many part asks both by reference and by the rows that exist now. Every log
     * written since references were introduced carries one — a creation and a deletion from the
     * whole row, an update from the row's references read alongside what it wrote — so the
     * reference finds a deleted row and every edit since. The ids are for logs written before
     * then. Between them the only history out of reach is that of a row deleted before references
     * were written, and, on a relation past {@link #CURRENT_ROWS_ASKED_BY_ID} rows, anything before
     * references at all.
     */
    List<HistoryPart> historyParts(String modelName, Serializable id, List<String> relations) {
        MetaModel metaModel = ModelManager.getModel(modelName);
        Assert.notTrue(metaModel.isTimeline(),
                "The timeline model can only call the API to get the slice change log: getSliceChangeLog");
        String rowId = String.valueOf(id);
        List<HistoryPart> parts = new ArrayList<>();
        parts.add(new HistoryPart(modelName, List.of(rowId), null, visibleFieldsOf(modelName)));

        List<String> oneToOne = new ArrayList<>();
        for (String relation : new LinkedHashSet<>(relations)) {
            MetaField field = ModelManager.getModelFieldOrNull(modelName, relation);
            Assert.notNull(field, "Model {0} has no field {1}.", modelName, relation);
            Assert.isTrue(FieldType.ONE_TO_ONE.equals(field.getFieldType())
                            || FieldType.ONE_TO_MANY.equals(field.getFieldType()),
                    "Field {0}.{1} is not a one-to-one or one-to-many relation.", modelName, relation);
            if (!mayRead(field.getRelatedModel())) {
                continue;
            }
            if (FieldType.ONE_TO_ONE.equals(field.getFieldType())) {
                oneToOne.add(relation);
            } else {
                List<String> current = this.logRowIds(field.getRelatedModel(),
                        Filters.of(field.getRelatedField(), Operator.EQUAL, id), CURRENT_ROWS_ASKED_BY_ID + 1);
                parts.add(new HistoryPart(field.getRelatedModel(),
                        current.size() > CURRENT_ROWS_ASKED_BY_ID ? List.of() : current,
                        field.getRelatedField() + "=" + rowId, visibleFieldsOf(field.getRelatedModel())));
            }
        }
        if (!oneToOne.isEmpty()) {
            Map<String, Object> row = modelService.getById(modelName, id, oneToOne).orElse(Map.of());
            for (String relation : oneToOne) {
                Object value = row.get(relation);
                if (value instanceof Map<?, ?> reference) {
                    value = reference.get(ModelConstant.ID);
                }
                if (value != null) {
                    String related = ModelManager.getModelField(modelName, relation).getRelatedModel();
                    parts.add(new HistoryPart(related,
                            this.logRowIds(related, Filters.of(ModelConstant.ID, Operator.EQUAL, value), 0),
                            null, visibleFieldsOf(related)));
                }
            }
        }
        return parts;
    }

    /**
     * The row ids the log keys these rows' entries by.
     *
     * <p>The business id, except on a timeline model: there one business id is several slices,
     * one per effective period, each written — and logged — as a row of its own under its slice
     * id. Asking a salary profile's history by its business id finds none of it. Every slice is
     * asked for, past and future, since each is part of the history.
     */
    private List<String> logRowIds(String model, Filters filters, int limit) {
        if (!ModelManager.getModel(model).isTimeline()) {
            List<Serializable> ids = limit > 0 ? modelService.getIds(model, filters, limit)
                    : modelService.getIds(model, filters);
            return ids.stream().map(String::valueOf).toList();
        }
        FlexQuery slices = new FlexQuery(Set.of(ModelConstant.SLICE_ID), filters).acrossTimelineData();
        if (limit > 0) {
            slices.setLimitSize(limit);
        }
        return modelService.searchList(model, slices).stream()
                .map(row -> row.get(ModelConstant.SLICE_ID))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .distinct()
                .toList();
    }

    /** Model-level read access; a relation the reader cannot read is left out of their history. */
    private boolean mayRead(String model) {
        try {
            permissionService.checkModelAccess(model, READ);
            return true;
        } catch (PermissionException e) {
            return false;
        }
    }

    /** Every stored field the reader may see, or null when their field sets hide none. */
    private List<String> visibleFieldsOf(String model) {
        Set<String> blocked = permissionService.getUserBlockedModelFields(model, READ);
        if (blocked == null || blocked.isEmpty()) {
            return null;
        }
        return ModelManager.getModelStoredFields(model).stream().filter(f -> !blocked.contains(f)).toList();
    }

    /**
     * The parts as one query: any part may match, each part is its model AND its rows.
     *
     * <p>Where the reader's field sets hide some of a model's fields, an update that wrote nothing
     * else is excluded here rather than only from the page, so it is left out of the count as well.
     * That needs the fields the update wrote, which logs carry only from when they began to; an
     * older one is still removed from the page, and is counted.
     */
    Query historyQuery(List<HistoryPart> parts, boolean includeCreation) {
        BoolQuery.Builder root = new BoolQuery.Builder().minimumShouldMatch("1");
        for (HistoryPart part : parts) {
            root.should(Query.of(q -> q.bool(b -> {
                b.filter(f -> f.term(t -> t.field("model").value(part.model())));
                b.filter(f -> f.bool(rows -> {
                    if (!part.rowIds().isEmpty()) {
                        rows.should(r -> r.terms(t -> t.field("rowId").terms(v -> v.value(
                                part.rowIds().stream().map(FieldValue::of).toList()))));
                    }
                    if (part.ref() != null) {
                        rows.should(r -> r.term(t -> t.field("refs").value(part.ref())));
                    }
                    return rows.minimumShouldMatch("1");
                }));
                if (part.visibleFields() != null) {
                    b.mustNot(m -> m.bool(onlyHidden -> {
                        onlyHidden.filter(f -> f.term(t -> t.field("accessType").value(UPDATE.name())));
                        onlyHidden.filter(f -> f.exists(e -> e.field("changedFields")));
                        if (!part.visibleFields().isEmpty()) {
                            onlyHidden.mustNot(n -> n.terms(t -> t.field("changedFields").terms(v -> v.value(
                                    part.visibleFields().stream().map(FieldValue::of).toList()))));
                        }
                        return onlyHidden;
                    }));
                }
                return b;
            })));
        }
        if (!includeCreation) {
            root.filter(f -> f.terms(t -> t.field("accessType").terms(v -> v.value(
                    List.of(FieldValue.of(UPDATE.name()), FieldValue.of(DELETE.name()))))));
        }
        // The log is one index for every tenant and this query bypasses searchPage, so the caller's
        // tenant is stated here. Every id in the parts was already read inside that tenant and ids
        // are unique across tenants, so this is the second line, not the first.
        Long tenantId = ContextHolder.getContext().getTenantId();
        if (tenantId != null) {
            root.filter(f -> f.term(t -> t.field("tenantId").value(String.valueOf(tenantId))));
        }
        return Query.of(q -> q.bool(root.build()));
    }

    /**
     * Only the logs of rows the reader may read.
     *
     * <p>A reader under no row scope keeps every log, a deleted row's included. Under a scope the
     * test is whether the row is still within it, which a deleted row cannot pass; its history is
     * out of reach for a scoped reader, which is the conservative answer for a row nobody can now
     * place inside or outside their scope.
     */
    List<ChangeLog> onReadableRows(String model, List<ChangeLog> logs) {
        if (logs.isEmpty() || Filters.isEmpty(permissionService.appendScopeAccessFilters(model, new Filters()))) {
            return logs;
        }
        List<Serializable> ids = IdUtils.formatIds(model,
                logs.stream().map(ChangeLog::getRowId).distinct().toList());
        Set<String> readable = new HashSet<>();
        modelService.getIds(model, Filters.of(ModelConstant.ID, Operator.IN, ids))
                .forEach(readableId -> readable.add(String.valueOf(readableId)));
        return logs.stream().filter(log -> readable.contains(log.getRowId())).toList();
    }

    /**
     * The logs with every field the reader's sensitive field sets do not grant taken out.
     *
     * <p>A model read masks those fields; the change log carried them through untouched, so anyone
     * who could read a row could read the before and after of its salary or account number here,
     * edit by edit. Applied at this one point because every read of the log passes through it —
     * by row, by slice, by model and the raw search alike.
     *
     * <p>Removed rather than nulled. A null reads as "the value was cleared", which is a statement
     * about the data that is not true; a field the reader may not see is simply not in their
     * history. An update that touched nothing else is dropped whole — an entry saying "something
     * changed here on this day" is itself what a field set exists to keep from them.
     *
     * <p>A deletion keeps only the fact that a row went. Its other fields are not hidden by the
     * reader's sets, but they describe a row the reader can no longer see anywhere else: a deleted
     * salary slice's effective dates say a pay period was withdrawn and when, which the salary
     * table — showing only the rows that remain — never told them. A creation keeps its visible
     * fields, since the row it describes is one the reader can still find.
     *
     * <p>A dropped entry still counts in the page's total, which comes from the index: the total is
     * an upper bound for a reader whose sets hide something, exact for everyone else.
     *
     * <p>Readers with full data access, and calls made with permission checks bypassed, are blocked
     * from nothing and get the logs as stored.
     */
    List<ChangeLog> withoutBlockedFields(List<ChangeLog> logs) {
        Map<String, Set<String>> blockedByModel = new HashMap<>();
        List<ChangeLog> visible = new ArrayList<>(logs.size());
        for (ChangeLog log : logs) {
            Set<String> blocked = blockedByModel.computeIfAbsent(log.getModel(),
                    model -> permissionService.getUserBlockedModelFields(model, READ));
            if (!blocked.isEmpty()) {
                log.setDataBeforeChange(without(log.getDataBeforeChange(), blocked));
                log.setDataAfterChange(without(log.getDataAfterChange(), blocked));
                if (UPDATE.equals(log.getAccessType())
                        && (log.getDataAfterChange() == null || log.getDataAfterChange().isEmpty())) {
                    continue;
                }
                if (DELETE.equals(log.getAccessType())) {
                    log.setDataBeforeChange(new HashMap<>());
                }
            }
            visible.add(log);
        }
        return visible;
    }

    private static Map<String, Object> without(Map<String, Object> data, Set<String> blocked) {
        if (data == null) {
            return null;
        }
        Map<String, Object> kept = new HashMap<>(data);
        kept.keySet().removeAll(blocked);
        return kept;
    }

    /**
     * Get the change log by the id of the business data model.
     *
     * @param modelName model name
     * @param id primary key id
     * @param order sort rule based on change time, default is reverse order, only support DESC, ASC string
     * @param includeCreation whether to include data at creation time, default is false, that is, not included
     * @return a page of change log list
     */
    public Page<ChangeLog> getChangeLog(String modelName, Serializable id, Page<ChangeLog> page, String order, boolean includeCreation) {
        // Check if current user has access to the model and id
        permissionService.checkIdsFieldsAccess(modelName, Collections.singletonList(id), null, READ);
        page = this.getRowChangeLog(modelName, id, page, order, includeCreation);
        return this.processChangeLogData(modelName, page, ConvertType.REFERENCE);
    }

    /**
     * Get the change log by the primary key of the timeline model.
     *
     * @param modelName model name
     * @param sliceId primary key of the timeline model
     * @param page page object
     * @param order sort rule based on change time, default is reverse order, only support DESC, ASC string
     * @param includeCreation whether to include data at creation time, default is false, that is, not included
     * @return a page of change log list
     */
    public Page<ChangeLog> getSliceChangeLog(String modelName, Serializable sliceId, Page<ChangeLog> page, String order, boolean includeCreation) {
        // Get the business ids of the timeline model
        List<Serializable> ids = modelService.getIds(modelName, Filters.of(SLICE_ID, Operator.EQUAL, sliceId));
        Assert.notEmpty(ids,
                "Timeline model {0} does not exist slice sliceId={1} data!", modelName, sliceId);
        // Check if current user has access to the timeline model and business id
        permissionService.checkIdsFieldsAccess(modelName, ids, null, READ);
        this.getRowChangeLog(modelName, sliceId, page, order, includeCreation);
        return this.processChangeLogData(modelName, page, ConvertType.REFERENCE);
    }

    /**
     * Get the ChangeLog page with the specified query conditions
     *
     * @param model     model name
     * @param flexQuery query conditions
     * @param page      page object
     * @return a page of list
     */
    public Page<ChangeLog> searchPageByModel(String model, FlexQuery flexQuery, Page<ChangeLog> page) {
        permissionService.checkModelAccess(model, READ);
        Filters filters = Filters.and(flexQuery.getFilters(), new Filters().eq(ChangeLog::getModel, model));
        Orders orders = flexQuery.getOrders();
        this.searchPage(filters, orders, page);
        page.setRows(onReadableRows(model, page.getRows()));
        ConvertType convertType = flexQuery.getConvertType();
        if (ConvertType.REFERENCE.equals(convertType) || ConvertType.DISPLAY.equals(convertType)) {
            // Enhance the field values in before and after data
            this.processChangeLogData(model, page, convertType);
        }
        return page;
    }

    /**
     * Get the ChangeLog list of the specified primary key.
     *
     * @param model     model name
     * @param pKey      primary key, corresponding to the sliceId of the timeline record
     * @param page      page object
     * @param order     sort rule, default is reverse order by change time, only support DESC, ASC string
     * @param includeCreation whether to include data at creation time, default is false, that is, not included
     * @return a page of ChangeLog list
     */
    public Page<ChangeLog> getRowChangeLog(String model, Serializable pKey, Page<ChangeLog> page, String order, boolean includeCreation) {
        // Default sort by changedTime in reverse order
        Orders orders = Orders.DESC.equals(order) ? Orders.ofDesc(ChangeLog::getChangedTime) : Orders.ofAsc(ChangeLog::getChangedTime);
        // Query the change log of the specified filters and rowId
        Filters filters = new Filters().eq(ChangeLog::getModel, model).eq(ChangeLog::getRowId, pKey.toString());
        if (!includeCreation) {
            // When not including the initial creation record, only match UPDATE and DELETE records
            filters.in(ChangeLog::getAccessType, Arrays.asList(UPDATE, DELETE));
        }
        return this.searchPage(filters, orders, page);
    }

    /**
     * Read the data change records according to the id of the business data model.
     *
     * @param modelName model name
     * @param page page object
     * @param convertType convert type
     * @return a page of change log list
     */
    Page<ChangeLog> processChangeLogData(String modelName, Page<ChangeLog> page, ConvertType convertType) {
        // Grouped by each log's own model: a record's history spans the models it is kept across,
        // and a field of one is meaningless to the metadata of another.
        Map<String, List<ChangeLog>> byModel = new LinkedHashMap<>();
        page.getRows().forEach(log -> byModel.computeIfAbsent(
                log.getModel() == null ? modelName : log.getModel(), m -> new ArrayList<>()).add(log));
        byModel.forEach((model, logs) -> this.processModelChangeLogData(model, logs, convertType));
        return page;
    }

    private void processModelChangeLogData(String modelName, List<ChangeLog> logs, ConvertType convertType) {
        List<Map<String, Object>> changeLogDataList = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        logs.forEach(changeLog -> {
            if (UPDATE.equals(changeLog.getAccessType())) {
                // UPDATE contains data before and after change
                fields.addAll(changeLog.getDataBeforeChange().keySet());
                changeLogDataList.add(changeLog.getDataBeforeChange());
                fields.addAll(changeLog.getDataAfterChange().keySet());
                changeLogDataList.add(changeLog.getDataAfterChange());
            } else if (CREATE.equals(changeLog.getAccessType())) {
                // CREATE only has data after change
                fields.addAll(changeLog.getDataAfterChange().keySet());
                changeLogDataList.add(changeLog.getDataAfterChange());
            }
        });
        // Enhance the field values in before and after data, only retain the fields existing in the metadata.
        // Fields the reader may not see are already gone: searchPage removes them from every log.
        fields.retainAll(ModelManager.getModelStoredFields(modelName));
        FlexQuery flexQuery = new FlexQuery(fields);
        flexQuery.setConvertType(convertType);
        // The read pipeline is built for rows that carry every field asked for; a log carries only
        // what it wrote, and the fields are the union across the page. Some processors write their
        // field whether the row had it or not — masking puts a masked null into every row — and on
        // a log that is a change it never made: one edit to a date of birth came back listing three
        // masked contact fields as changed from nothing to nothing, borrowed from another log on the
        // same page. Each map keeps only the keys it had.
        List<Set<String>> ownKeys = changeLogDataList.stream()
                .map(data -> (Set<String>) new HashSet<>(data.keySet())).toList();
        dataPipelineProxy.processReadData(modelName, flexQuery, changeLogDataList);
        for (int i = 0; i < changeLogDataList.size(); i++) {
            changeLogDataList.get(i).keySet().retainAll(ownKeys.get(i));
        }
    }

    /**
     * Drop from each entry the fields the caller may not see on that record — its before and after
     * values alike, so the entry neither shows the value nor what it was changed from. Each entry is
     * judged as a row of its own model, so a page mixing models is masked correctly.
     *
     * <p>Judged per record, exactly as a read of the record would be: the values are masked by the
     * permission service as rows of the model, keyed by the record's id. A field hidden on either side
     * is removed from both.
     */
    private void maskInaccessibleFields(List<ChangeLog> changeLogs) {
        // One mask call per model for the whole page, not one per entry: each call may ask which of
        // the rows the caller's roles reach.
        Map<String, List<ChangeLog>> byModel = new LinkedHashMap<>();
        for (ChangeLog changeLog : changeLogs) {
            if (changeLog.getModel() != null) {
                byModel.computeIfAbsent(changeLog.getModel(), k -> new ArrayList<>()).add(changeLog);
            }
        }
        byModel.forEach((modelName, logs) -> {
            List<List<Map<String, Object>>> sidesByLog = new ArrayList<>(logs.size());
            List<Map<String, Object>> masked = new ArrayList<>();
            for (ChangeLog changeLog : logs) {
                List<Map<String, Object>> sides = new ArrayList<>(2);
                if (changeLog.getDataBeforeChange() != null) sides.add(changeLog.getDataBeforeChange());
                if (changeLog.getDataAfterChange() != null) sides.add(changeLog.getDataAfterChange());
                sidesByLog.add(sides);
                // Masked as copies carrying the record's id, so the stored maps are only ever pruned.
                for (Map<String, Object> side : sides) {
                    Map<String, Object> copy = new HashMap<>(side);
                    copy.putIfAbsent(ModelConstant.ID, changeLog.getRowId());
                    masked.add(copy);
                }
            }
            if (masked.isEmpty()) return;
            permissionService.maskRows(modelName, masked);
            int next = 0;
            for (List<Map<String, Object>> sides : sidesByLog) {
                Set<String> hidden = new HashSet<>();
                for (Map<String, Object> side : sides) {
                    Map<String, Object> maskedSide = masked.get(next++);
                    for (Map.Entry<String, Object> entry : side.entrySet()) {
                        if (entry.getValue() != null && maskedSide.get(entry.getKey()) == null) {
                            hidden.add(entry.getKey());
                        }
                    }
                }
                if (!hidden.isEmpty()) {
                    sides.forEach(side -> side.keySet().removeAll(hidden));
                }
            }
        });
    }

}
