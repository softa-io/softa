package io.softa.starter.studio.template.ddl.dialect;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.studio.template.ddl.context.ModelDdlCtx;

public interface DdlDialect {

    /**
     * Database type handled by this dialect implementation.
     */
    DatabaseType getDatabaseType();

    /**
     * DDL statement to create a table
     *
     * @param model Design model DDL context
     * @return Create table DDL statement
     */
    StringBuilder createTableDDL(ModelDdlCtx model);

    /**
     * DDL statement to drop a table
     *
     * @param model Design model DDL context
     * @return Drop table DDL statement
     */
    StringBuilder dropTableDDL(ModelDdlCtx model);

    /**
     * DDL statement to alter a table
     *
     * @param model Design model DDL context
     * @return Alter table DDL statement
     */
    StringBuilder alterTableDDL(ModelDdlCtx model);

    /**
     * DDL statement to alter indexes, including renaming, deleting and creating indexes.
     */
    StringBuilder alterIndexDDL(ModelDdlCtx model);
}
