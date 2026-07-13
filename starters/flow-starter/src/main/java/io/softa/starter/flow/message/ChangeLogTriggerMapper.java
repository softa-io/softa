package io.softa.starter.flow.message;

import java.util.*;

import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.enums.AccessType;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;

/**
 * Maps ORM {@link ChangeLog} entries to {@link FlowTriggerEvent} instances
 * suitable for firing through the flow trigger engine.
 * <p>
 * Shared by {@code ChangeLogFlowConsumer} (async MQ) and
 * {@code TransactionListenerForFlow} (sync in-transaction).
 * </p>
 */
public final class ChangeLogTriggerMapper {

    /** Event-parameter key carrying the change {@link AccessType} name (CREATE/UPDATE/DELETE/READ). */
    public static final String PARAM_ACCESS_TYPE = "_accessType";
    /** Event-parameter key carrying the changed row id. */
    public static final String PARAM_ROW_ID = "_rowId";
    /** Event-parameter key carrying the changed model name. */
    public static final String PARAM_MODEL = "_model";

    private ChangeLogTriggerMapper() {
    }

    /**
     * Map a list of change logs to trigger events.
     *
     * @param changeLogs the change logs from a transaction or MQ message
     * @param actorId    the actor id (user who initiated the change)
     * @return list of trigger events, one per change log
     */
    public static List<FlowTriggerEvent> mapChangeLogs(List<ChangeLog> changeLogs, String actorId) {
        if (changeLogs == null || changeLogs.isEmpty()) {
            return List.of();
        }
        List<FlowTriggerEvent> events = new ArrayList<>();
        for (ChangeLog changeLog : changeLogs) {
            events.add(mapSingle(changeLog, actorId));
        }
        return events;
    }

    private static FlowTriggerEvent mapSingle(ChangeLog changeLog, String actorId) {
        Map<String, Object> parameters = new LinkedHashMap<>();

        // Include data payload
        Map<String, Object> data = resolveData(changeLog);
        if (data != null) {
            parameters.putAll(data);
        }

        // Include change metadata
        parameters.put(PARAM_ACCESS_TYPE, changeLog.getAccessType() == null ? null : changeLog.getAccessType().name());
        parameters.put(PARAM_ROW_ID, changeLog.getRowId());
        parameters.put(PARAM_MODEL, changeLog.getModel());

        return FlowTriggerEvent.builder()
                .type("EntityChange")
                .sourceModel(changeLog.getModel())
                .sourceRowId(changeLog.getRowId())
                .parameters(parameters)
                .actorId(actorId)
                .build();
    }

    /**
     * For DELETE, use data-before-change; otherwise use data-after-change.
     */
    private static Map<String, Object> resolveData(ChangeLog changeLog) {
        if (AccessType.DELETE.equals(changeLog.getAccessType())) {
            return changeLog.getDataBeforeChange() != null
                    ? changeLog.getDataBeforeChange()
                    : Collections.emptyMap();
        }
        return changeLog.getDataAfterChange() != null
                ? changeLog.getDataAfterChange()
                : Collections.emptyMap();
    }
}

