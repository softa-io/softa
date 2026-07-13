package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code TRANSFORM} nodes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractTransformConfig {

    /** Variable holding the source collection: a {@code {{ var }}} placeholder or a direct key (required). */
    private String collectionVariable;

    /** Map key to extract from each collection row (required). */
    private String itemKey;
}
