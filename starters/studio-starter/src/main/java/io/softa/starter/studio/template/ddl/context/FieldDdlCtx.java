package io.softa.starter.studio.template.ddl.context;

import lombok.Data;

import io.softa.framework.orm.enums.FieldType;

/**
 * Field-level DDL context passed to templates.
 */
@Data
public class FieldDdlCtx {
    private String fieldName;
    private String columnName;
    private String oldColumnName;
    private boolean renamed;
    private String labelName;
    private String description;
    private FieldType fieldType;
    private String dbType;
    private String typeDeclaration;
    private Integer length;
    private Integer scale;
    private boolean required;
    private boolean autoIncrement;
    private String defaultValue;
}
