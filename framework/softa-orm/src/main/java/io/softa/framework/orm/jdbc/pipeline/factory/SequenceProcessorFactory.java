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
 *   <li>A {@link SequenceService} is registered in {@link SequenceServiceHolder}
 *       (i.e. a sequence implementation starter is on the classpath).</li>
 * </ul>
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
            return null;
        }
        return new SequenceProcessor(metaField, accessType, service);
    }
}
