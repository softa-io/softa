package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.io.Serializable;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.IDGenerator;
import io.softa.framework.orm.utils.IdUtils;

/**
 * Fills the LOGICAL id of a timeline model on create. On a timeline model the physical
 * primary key is {@code sliceId} (covered by {@link IdProcessor}); the shared logical
 * {@code id} is an ordinary stored column that every slice of one entity carries, so it
 * must be app-generated for a FIRST slice and preserved on split/copy rows that arrive
 * already carrying an existing entity's id. {@code DB_AUTO_ID} cannot fill it (the
 * auto-increment lands on {@code sliceId}) and is rejected for timeline models at boot.
 */
public class TimelineLogicalIdProcessor extends BaseProcessor {

    public TimelineLogicalIdProcessor(MetaField metaField, AccessType accessType) {
        super(metaField, accessType);
    }

    /**
     * Fill the logical id when absent; rows carrying a valid id (slice splits/corrections
     * of an existing entity) are left untouched.
     *
     * @param rows rows to be created
     */
    @Override
    public void batchProcessInputRows(List<Map<String, Object>> rows) {
        IdStrategy idStrategy = ModelManager.getIdStrategy(modelName);
        if (IdStrategy.EXTERNAL_ID.equals(idStrategy)) {
            rows.forEach(row -> Assert.isTrue(IdUtils.validId(row.get(fieldName)),
                    "The idStrategy of timeline model {0} is `ExternalID`, so the logical id cannot be empty! {1}",
                    modelName, row));
            return;
        }
        Supplier<Serializable> idGenerator = switch (idStrategy) {
            case DISTRIBUTED_LONG -> IDGenerator::generateLongId;
            case DISTRIBUTED_STRING -> IDGenerator::generateStringId;
            // DB_AUTO_ID is rejected for timeline models at boot (ModelManager.validateTimelineFields).
            default -> throw new IllegalStateException(
                    "Timeline model " + modelName + " cannot generate a logical id under idStrategy " + idStrategy);
        };
        rows.forEach(row -> {
            if (!IdUtils.validId(row.get(fieldName))) {
                row.put(fieldName, idGenerator.get());
            }
        });
    }
}
