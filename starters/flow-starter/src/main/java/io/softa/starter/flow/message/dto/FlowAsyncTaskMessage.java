package io.softa.starter.flow.message.dto;

import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.framework.base.context.Context;

/**
 * Flow async task message
 */
@Data
@NoArgsConstructor
public class FlowAsyncTaskMessage {

    private Long flowId;
    private Long nodeId;
    private String asyncTaskHandlerCode;
    private Map<String, Object> asyncTaskParams;

    private Context context;
}
