package io.softa.starter.flow.message.dto;

import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.framework.base.context.Context;

/**
 * Message DTO for asynchronous task execution via Pulsar.
 */
@Data
@NoArgsConstructor
public class FlowAsyncTaskMessage {

    private String instanceId;
    private String flowCode;
    private String nodeId;
    private String asyncTaskHandlerCode;
    private Map<String, Object> asyncTaskParams;

    private Context context;
}

