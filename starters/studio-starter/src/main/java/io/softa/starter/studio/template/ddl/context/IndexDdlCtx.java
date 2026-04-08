package io.softa.starter.studio.template.ddl.context;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

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
}
