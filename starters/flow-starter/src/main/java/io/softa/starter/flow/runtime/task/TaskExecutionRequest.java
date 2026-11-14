package io.softa.starter.flow.runtime.task;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * Normalized task execution request built from config.task.*.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionRequest {

    private Map<String, Object> input;
    /**
     * Parsed typed input config for migrated (typed) executors — a {@code *Config} from
     * {@code runtime.nodeconfig} keyed by {@link io.softa.starter.flow.runtime.nodeconfig.TaskConfigTypes}.
     * Null for legacy executors, which read the generically-resolved {@link #input} instead.
     */
    private Object config;

    /** The executing node's id — available to executors that need to reference the node (e.g. async callbacks). */
    private String nodeId;
    /** The flow instance id — available to executors that need to reference the instance (e.g. async callbacks). */
    private String instanceId;
}
