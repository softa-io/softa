package io.softa.starter.flow.runtime.nodeconfig;

import java.util.EnumMap;
import java.util.Map;

import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Single source of truth mapping a task {@link FlowNodeType} to its typed input-config DTO.
 * <p>
 * Every task node type is <em>typed</em>: the runtime handler hands its executor the RAW,
 * unresolved input plus a parsed DTO (the executor owns placeholder / type-aware resolution),
 * and the compiler validates the input against the DTO shape at publish time. This map is a
 * static SSoT — deliberately not derived from the executor beans, so design-time validation
 * stays identical whether or not an optional executor (e.g. QueryAI) is wired in the runtime.
 * {@code DefaultTaskExecutorRegistry} asserts at boot that every registered executor's node
 * type has an entry here, so a new executor cannot silently ship without compile coverage.
 */
public final class TaskConfigTypes {

    private static final Map<FlowNodeType, Class<?>> TYPES = new EnumMap<>(FlowNodeType.class);

    static {
        TYPES.put(FlowNodeType.CREATE_RECORD, CreateDataConfig.class);
        TYPES.put(FlowNodeType.UPDATE_RECORD, UpdateDataConfig.class);
        TYPES.put(FlowNodeType.GET_RECORD, GetDataConfig.class);
        TYPES.put(FlowNodeType.DELETE_RECORD, DeleteDataConfig.class);
        TYPES.put(FlowNodeType.QUERY_RECORDS, QueryRecordsConfig.class);
        TYPES.put(FlowNodeType.CALL_SERVICE, CallServiceConfig.class);
        TYPES.put(FlowNodeType.SEND_SMS, SendSmsConfig.class);
        TYPES.put(FlowNodeType.SEND_EMAIL, SendEmailConfig.class);
        TYPES.put(FlowNodeType.SEND_INBOX_NOTIFICATION, SendInboxNotificationConfig.class);
        TYPES.put(FlowNodeType.GENERATE_FILE, GenerateFileConfig.class);
        TYPES.put(FlowNodeType.VALIDATE_DATA, ValidateDataConfig.class);
        TYPES.put(FlowNodeType.TRANSFORM, ExtractTransformConfig.class);
        TYPES.put(FlowNodeType.QUERY_AI, QueryAiConfig.class);
        TYPES.put(FlowNodeType.CALL_WEBHOOK, WebHookConfig.class);
        TYPES.put(FlowNodeType.ASYNC_TASK, AsyncTaskConfig.class);
    }

    private TaskConfigTypes() {
    }

    /** The typed config class for {@code nodeType}, or {@code null} for non-task node types. */
    public static Class<?> forNodeType(FlowNodeType nodeType) {
        return nodeType == null ? null : TYPES.get(nodeType);
    }
}
