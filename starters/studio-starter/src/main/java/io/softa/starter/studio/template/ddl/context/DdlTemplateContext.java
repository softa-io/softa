package io.softa.starter.studio.template.ddl.context;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * Top-level DDL template context grouped by model lifecycle.
 */
@Getter
public class DdlTemplateContext {
    private final List<ModelDdlCtx> createdModels = new ArrayList<>();
    private final List<ModelDdlCtx> deletedModels = new ArrayList<>();
    private final List<ModelDdlCtx> updatedModels = new ArrayList<>();
}
