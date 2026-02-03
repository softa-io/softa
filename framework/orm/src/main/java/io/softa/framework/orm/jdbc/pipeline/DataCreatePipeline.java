package io.softa.framework.orm.jdbc.pipeline;

import io.softa.framework.base.enums.AccessType;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.jdbc.AutofillFields;
import io.softa.framework.orm.jdbc.pipeline.chain.FieldProcessorChain;
import io.softa.framework.orm.jdbc.pipeline.chain.FieldProcessorFactoryChain;
import io.softa.framework.orm.jdbc.pipeline.factory.*;
import io.softa.framework.orm.jdbc.pipeline.processor.IdProcessor;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Pipeline for creating model data.
 * Including batch encryption, data format conversion, default value assignment, computed fields calculation,
 * and length, required, read-only and other attribute verification.
 * <p>
 * When processing the associated OneToMany, ManyToMany fields, without permission check temporarily.
 */
@Slf4j
@Getter
public class DataCreatePipeline extends DataPipeline {

    private static final AccessType accessType = AccessType.CREATE;

    /** The set of stored fields */
    protected Set<String> storedFields;

    /**
     * Build the CREATE pipeline.
     * All stored fields are assigned specified values or default values.
     *
     * @param modelName model name
     */
    public DataCreatePipeline(String modelName) {
        super(modelName);
        this.fields = ModelManager.getModelUpdatableFields(modelName);
        this.addEffectedFields();
        // Extract the set of fields stored in the database
        this.storedFields = fields.stream().filter(field -> ModelManager.isStored(modelName, field)).collect(Collectors.toSet());
        this.storedFields.addAll(ModelManager.getModel(modelName).getAuditCreateFields());
        this.storedFields.addAll(ModelManager.getModel(modelName).getAuditUpdateFields());
        // Build the field processor chain
        this.processorChain = buildFieldProcessorChain();
        // If the IdStrategy is not DB_AUTO_ID, add the ID field to the list of fields to be processed.
        if (!IdStrategy.DB_AUTO_ID.equals(ModelManager.getIdStrategy(modelName))) {
            MetaField pkField = ModelManager.getModelPrimaryKeyField(modelName);
            this.processorChain.addProcessor(new IdProcessor(pkField, accessType));
            this.storedFields.add(pkField.getFieldName());
        }
        if (ModelManager.isMultiTenant(modelName)) {
            this.storedFields.add(ModelConstant.TENANT_ID);
        }
    }

    /**
     * Add the stored cascaded fields and stored computed fields.
     */
    private void addEffectedFields() {
        MetaModel metaModel = ModelManager.getModel(modelName);
        metaModel.getStoredCascadedFields().forEach(metaField -> this.fields.add(metaField.getFieldName()));
        metaModel.getStoredComputedFields().forEach(metaField -> this.fields.add(metaField.getFieldName()));
    }

    /**
     * Create processor factory chain according to the data processing order of the creation scenario,
     * and generate the final field processing responsibility chain `FieldProcessorChain`.
     */
    @Override
    public FieldProcessorChain buildFieldProcessorChain() {
        FieldProcessorFactoryChain factoryChain = FieldProcessorFactoryChain.of(modelName, accessType)
                .addFactory(new XToOneGroupProcessorFactory())
                .addFactory(new NormalProcessorFactory())
                .addFactory(new ComputeProcessorFactory())
                .addFactory(new TypeCastProcessorFactory())
                // Encrypt the `encrypted` fields before storing them in the database.
                .addFactory(new EncryptProcessorFactory());
        return factoryChain.generateProcessorChain(fields);
    }

    /**
     * Process the rows data of Create.
     *
     * @param rows rows
     * @param createdTime creation time
     */
    @Override
    public List<Map<String, Object>> processCreateData(List<Map<String, Object>> rows, LocalDateTime createdTime) {
        // Format the field data of the current model
        processorChain.processInputRows(rows);
        // Fill in the audit fields
        AutofillFields.fillAuditFieldsForInsert(modelName, rows, createdTime);
        // Fill in the tenant field for multi-tenant models
        AutofillFields.fillTenantFieldForInsert(modelName, rows);
        return rows;
    }

    /**
     * After obtaining the ids, process the OneToMany and ManyToMany fields of current model.
     *
     * @param rows rows
     */
    @Override
    public boolean processXToManyData(List<Map<String, Object>> rows) {
        FieldProcessorFactoryChain xToManyFactoryChain = FieldProcessorFactoryChain.of(modelName, accessType)
                .addFactory(new XToManyProcessorFactory());
        xToManyFactoryChain.generateProcessorChain(fields).processInputRows(rows);
        return true;
    }
}
