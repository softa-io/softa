package io.softa.framework.orm.jdbc.pipeline.factory;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.jdbc.pipeline.processor.FieldProcessor;
import io.softa.framework.orm.jdbc.pipeline.processor.SequenceProcessor;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.sequence.SequenceService;
import io.softa.framework.orm.sequence.SequenceServiceHolder;

/**
 * Factory for the sequence-fill processor. Active only when:
 * <ul>
 *   <li>{@code accessType == CREATE} — sequence allocation runs only on insert.</li>
 *   <li>{@code metaField.isAutoSequence() == true} — declared in {@code sys_field}
 *       metadata via the {@code auto_sequence} column (loaded by
 *       {@code ModelManager.init()} just like {@code readonly} / {@code required}).</li>
 * </ul>
 *
 * <p>An {@code autoSequence} field with no {@link SequenceService} registered in
 * {@link SequenceServiceHolder} is a deployment error (the flag is present in the
 * shared metadata but no sequence implementation starter is on the classpath) and
 * fails the insert loudly — silently skipping would fill the field with the static
 * default / blank and violate the "declared means allocated" contract.
 *
 * <p>Designed to be inserted before {@code NormalProcessorFactory} in
 * {@code DataCreatePipeline.buildFieldProcessorChain}, so the sequence-filled
 * value beats any static {@code defaultValue} fallback.
 */
public class SequenceProcessorFactory implements FieldProcessorFactory {

    @Override
    public FieldProcessor createProcessor(MetaField metaField, AccessType accessType) {
        if (!AccessType.CREATE.equals(accessType)) {
            return null;
        }
        if (!metaField.isAutoSequence()) {
            return null;
        }
        SequenceService service = SequenceServiceHolder.get();
        if (service == null) {
            throw new IllegalStateException(
                    "Field " + metaField.getModelName() + "." + metaField.getFieldName()
                            + " is declared autoSequence=true, but no SequenceService implementation"
                            + " is present — add metadata-starter to the application, or remove the flag.");
        }
        return new SequenceProcessor(metaField, accessType, service);
    }
}
