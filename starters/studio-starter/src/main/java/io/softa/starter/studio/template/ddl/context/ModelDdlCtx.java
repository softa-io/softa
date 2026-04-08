package io.softa.starter.studio.template.ddl.context;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

import io.softa.framework.orm.enums.IdStrategy;

/**
 * Model-level DDL context passed to templates.
 */
@Data
public class ModelDdlCtx {
    private String modelName;
    private String labelName;
    private String description;
    private String tableName;
    private String oldTableName;
    private String pkColumn;
    private IdStrategy idStrategy;
    private boolean autoIncrementPrimaryKey;
    private boolean renamed;
    private boolean descriptionChanged;
    private final List<FieldDdlCtx> createdFields = new ArrayList<>();
    private final List<FieldDdlCtx> deletedFields = new ArrayList<>();
    private final List<FieldDdlCtx> updatedFields = new ArrayList<>();
    private final List<FieldDdlCtx> renamedFields = new ArrayList<>();
    private final List<IndexDdlCtx> createdIndexes = new ArrayList<>();
    private final List<IndexDdlCtx> deletedIndexes = new ArrayList<>();
    private final List<IndexDdlCtx> updatedIndexes = new ArrayList<>();
    private final List<IndexDdlCtx> renamedIndexes = new ArrayList<>();

    public boolean isHasFieldChanges() {
        return !createdFields.isEmpty()
                || !deletedFields.isEmpty()
                || !updatedFields.isEmpty()
                || !renamedFields.isEmpty();
    }

    public boolean isHasIndexChanges() {
        return !createdIndexes.isEmpty()
                || !deletedIndexes.isEmpty()
                || !updatedIndexes.isEmpty()
                || !renamedIndexes.isEmpty();
    }

    public boolean isHasAlterTableChanges() {
        return descriptionChanged || isHasFieldChanges();
    }
}
