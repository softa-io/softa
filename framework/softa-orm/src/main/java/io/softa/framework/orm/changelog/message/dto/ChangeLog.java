package io.softa.framework.orm.changelog.message.dto;

import java.io.Serializable;
import java.util.Map;
import lombok.Data;

import io.softa.framework.orm.enums.AccessType;

/**
 * ChangeLog DTO
 */
@Data
public class ChangeLog {

    // ChangeLog uuid
    private String uuid;

    // Trace ID for distributed tracing
    private String traceId;

    // Correlation ID for business context
    private String correlationId;

    private String model;
    private Serializable rowId;
    private AccessType accessType;

    private Map<String, Object> dataBeforeChange;
    private Map<String, Object> dataAfterChange;

    private Long tenantId;
    private Long changedById;
    private String changedBy;
    private String changedTime;

}
