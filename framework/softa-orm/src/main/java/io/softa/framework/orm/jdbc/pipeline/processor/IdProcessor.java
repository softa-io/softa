package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.enums.AccessType;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.IDGenerator;
import io.softa.framework.orm.utils.IdUtils;

/**
 * ID field processor
 * Fill in the id field according to the model's primary key policy.
 */
public class IdProcessor extends BaseProcessor {

    public IdProcessor(MetaField metaField, AccessType accessType) {
        super(metaField, accessType);
    }

    /**
     * Generate or process primary key based on idStrategy when creating data.
     *
     * @param rows List of data to be created
     */
    private void processIdByIdStrategy(Collection<Map<String, Object>> rows) {
        IdStrategy idStrategy = ModelManager.getIdStrategy(modelName);
        switch (idStrategy) {
            case DB_AUTO_ID:
                // Skip auto-increment ID
                return;
            case DISTRIBUTED_LONG:
                generateIds(rows, IDGenerator::generateLongId);
                break;
            case DISTRIBUTED_STRING:
                generateIds(rows, IDGenerator::generateStringId);
                break;
            case EXTERNAL_ID:
                formatExternalIds(rows);
                break;
            default:
                throw new IllegalArgumentException("Unknown ID strategy: " + idStrategy);
        }
    }

    /**
     * Generate ID for each row
     *
     * @param rows List of data to be created
     * @param idGenerator ID generator
     */
    private void generateIds(Collection<Map<String, Object>> rows, Supplier<Serializable> idGenerator) {
        if (SystemConfig.env.isEnableInsertId()) {
            rows.forEach(row -> {
                if (!IdUtils.validId(row.get(fieldName))) {
                    row.put(fieldName, idGenerator.get());
                }
            });
        } else {
            rows.forEach(row -> row.put(fieldName, idGenerator.get()));
        }
    }

    /**
     * Format external ID
     *
     * @param rows List of data to be created
     */
    private void formatExternalIds(Collection<Map<String, Object>> rows) {
        rows.forEach(row -> {
            Serializable idValue = (Serializable) row.get(fieldName);
            Assert.isTrue(IdUtils.validId(idValue),
                    "The idStrategy of Model {0} is `ExternalID`, so ID cannot be empty!", modelName);
            row.put(fieldName, IdUtils.formatId(metaField.getFieldType(), idValue));
        });
    }

    @Override
    public void batchProcessInputRows(List<Map<String, Object>> rows) {
        if (AccessType.CREATE.equals(accessType)) {
            this.processIdByIdStrategy(rows);
        }
    }
}
