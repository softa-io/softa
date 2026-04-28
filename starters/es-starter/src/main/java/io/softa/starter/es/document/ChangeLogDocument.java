package io.softa.starter.es.document;

import java.util.Map;
import lombok.Data;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.enums.AccessType;

/**
 * Storage form of {@link ChangeLog} in Elasticsearch.
 * The two payload maps are flattened to a JSON string so that the round-trip
 * through the ES Java client's JsonpMapper is deterministic regardless of how
 * its internal {@code ObjectMapper} is configured.
 */
@Data
public class ChangeLogDocument {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private String uuid;
    private String traceId;
    private String correlationId;
    private String model;
    private String rowId;
    private AccessType accessType;
    private String dataBeforeChange;
    private String dataAfterChange;
    private Long tenantId;
    private Long changedById;
    private String changedBy;
    private String changedTime;

    public static ChangeLogDocument fromChangeLog(ChangeLog source) {
        ChangeLogDocument doc = new ChangeLogDocument();
        doc.uuid = source.getUuid();
        doc.traceId = source.getTraceId();
        doc.correlationId = source.getCorrelationId();
        doc.model = source.getModel();
        doc.rowId = source.getRowId();
        doc.accessType = source.getAccessType();
        doc.dataBeforeChange = JsonUtils.objectToString(source.getDataBeforeChange());
        doc.dataAfterChange = JsonUtils.objectToString(source.getDataAfterChange());
        doc.tenantId = source.getTenantId();
        doc.changedById = source.getChangedById();
        doc.changedBy = source.getChangedBy();
        doc.changedTime = source.getChangedTime();
        return doc;
    }

    public static ChangeLog toChangeLog(ChangeLogDocument doc) {
        ChangeLog cl = new ChangeLog();
        cl.setUuid(doc.uuid);
        cl.setTraceId(doc.traceId);
        cl.setCorrelationId(doc.correlationId);
        cl.setModel(doc.model);
        cl.setRowId(doc.rowId);
        cl.setAccessType(doc.accessType);
        cl.setDataBeforeChange(JsonUtils.stringToObject(doc.dataBeforeChange, MAP_TYPE));
        cl.setDataAfterChange(JsonUtils.stringToObject(doc.dataAfterChange, MAP_TYPE));
        cl.setTenantId(doc.tenantId);
        cl.setChangedById(doc.changedById);
        cl.setChangedBy(doc.changedBy);
        cl.setChangedTime(doc.changedTime);
        return cl;
    }
}
