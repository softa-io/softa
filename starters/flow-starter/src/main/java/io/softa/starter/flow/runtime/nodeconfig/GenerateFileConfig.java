package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code GENERATE_FILE} nodes.
 * <p>
 * Fields hold the raw authored values; resolution is owned by the executor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateFileConfig {

    /** Document template id: a number, or a numeric string (required). */
    private Object templateId;

    /** Business row id to render with; when blank the flow variables are the render context. */
    private String rowId;
}
