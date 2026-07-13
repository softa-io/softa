package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code CALL_WEBHOOK} nodes.
 * <p>
 * The envelope keys are fixed; {@code headers} / {@code body} are free-shape payloads
 * whose {@code {{ }}} placeholders the executor resolves recursively.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebHookConfig {

    /** Target URL; supports {@code {{ }}} interpolation (required). */
    private String url;

    /** HTTP method; defaults to POST. */
    private String method;

    /** Free-shape request headers (map). */
    private Object headers;

    /** Free-shape request body. */
    private Object body;
}
