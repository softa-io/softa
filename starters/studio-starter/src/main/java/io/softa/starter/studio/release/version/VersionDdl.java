package io.softa.starter.studio.release.version;

import java.util.List;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.studio.release.dto.ModelChangesDTO;

/**
 * Version control DDL generation, including table structure and indexes
 */
public interface VersionDdl {
    /**
     * Generate structured DDL output (table + index) from a list of model changes.
     *
     * @param databaseType database dialect type
     * @param mergedChanges list of model-level change summaries
     * @return structured DDL result
     */
    VersionDdlResult generateDdlResult(DatabaseType databaseType, List<ModelChangesDTO> mergedChanges);

    /**
     * Analyze and construct table structure DDL statements based on model and field change records
     *
     * @param modelChanges Model change records, including add, modify, delete records
     * @param fieldChanges Field change records, including add, modify, delete records
     * @return DDL SQL related to table structure
     */
    String generateTableDDL(DatabaseType databaseType, ModelChangesDTO modelChanges, ModelChangesDTO fieldChanges);

    /**
     * Extract new index, delete index records from index change records, update operations on indexName,
     * indexFields, uniqueIndex will also be converted to delete index and create index.
     * For scenarios where the model is not modified but only fields are added, modified, or deleted,
     * attach these fields to a newly created design model object.
     *
     * @param indexChangeList Index change records, including add, modify, delete records
     * @return DDL SQL related to table structure
     */
    String generateIndexDDL(DatabaseType databaseType, ModelChangesDTO indexChangeList);

    /**
     * Generate combined DDL (table + index) from a list of model changes.
     * Extracts DesignModel, DesignField, and DesignModelIndex changes from the list
     * and generates the corresponding DDL statements.
     *
     * @param mergedChanges list of model-level change summaries
     * @return combined DDL string (table DDL + index DDL)
     */
    String generateDDL(DatabaseType databaseType, List<ModelChangesDTO> mergedChanges);
}
