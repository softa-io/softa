package io.softa.framework.orm.sequence;

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Static accessor for the application-scoped {@link SequenceService},
 * intended for non-DI components such as
 * {@code io.softa.framework.orm.jdbc.pipeline.DataCreatePipeline} which is
 * {@code new}'d per insert call rather than being a Spring bean.
 *
 * <p>The class is itself a Spring {@code @Component}: its constructor takes
 * an {@code Optional<SequenceService>} so the framework keeps working even
 * when no sequence-implementation starter is on the classpath. At
 * {@code @PostConstruct} it copies the resolved service (if any) into a
 * static volatile field, exposing it via {@link #get()}.
 *
 * <p>{@code MetaField.autoSequence} now lives directly in the {@code sys_field}
 * metadata table and is auto-loaded by {@code ModelManager.init()} — so
 * there is no longer any registry to populate or rebuild. This holder only
 * carries the reference to the live service instance.
 */
@Component
public class SequenceServiceHolder {

    private static volatile SequenceService instance;

    private final Optional<SequenceService> sequenceService;

    public SequenceServiceHolder(Optional<SequenceService> sequenceService) {
        this.sequenceService = sequenceService;
    }

    @PostConstruct
    void install() {
        sequenceService.ifPresent(SequenceServiceHolder::set);
    }

    /**
     * @return the registered service, or {@code null} if no sequence-starter
     *         is on the classpath.
     */
    public static SequenceService get() {
        return instance;
    }

    /** Test helper. */
    public static void set(SequenceService service) {
        instance = service;
    }

    /** Test helper. */
    public static void clear() {
        instance = null;
    }
}
