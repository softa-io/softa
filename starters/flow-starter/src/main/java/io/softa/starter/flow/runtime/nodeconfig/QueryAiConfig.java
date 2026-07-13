package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code QUERY_AI} nodes.
 * <p>
 * Fields hold the raw authored values; {@code queryContent} interpolation is owned
 * by the executor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryAiConfig {

    /** Prompt sent to the robot; supports {@code {{ }}} interpolation (required). */
    private String queryContent;

    /** Target robot id. */
    private Object robotId;

    /** Existing conversation id to continue. */
    private Object conversationId;
}
