package io.softa.starter.metadata.ddl.context;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

import io.softa.framework.orm.enums.IndexType;

/**
 * Index-level DDL context passed to templates.
 */
@Data
public class IndexDdlCtx {
    private String indexName;
    private String oldIndexName;
    private boolean renamed;
    private boolean definitionChanged;
    private List<String> columns = new ArrayList<>();
    private boolean unique;

    /**
     * What the index is for. Never null here — a null {@code sys_model_index.index_type} normalizes
     * to {@link IndexType#BTREE} on the way in, so no template has to reason about absence.
     */
    private IndexType indexType = IndexType.BTREE;

    /**
     * Read by the templates as {@code index.trigram}.
     *
     * <p>A derived getter rather than a value precomputed by the dialect: the Pebble templates are
     * also rendered directly from a hand-built context by {@code PebbleSqlTemplateWhitespaceTest},
     * bypassing the dialect entirely, so anything a dialect had to populate would be silently null
     * on that path and the goldens would lock in broken output.
     */
    public boolean isTrigram() {
        return indexType == IndexType.TRIGRAM;
    }
}
