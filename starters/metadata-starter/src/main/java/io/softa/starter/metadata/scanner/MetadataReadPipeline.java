package io.softa.starter.metadata.scanner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.softa.starter.metadata.config.MetadataProperties;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.scanner.annotation.AnnotationParser;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;
import io.softa.starter.metadata.scanner.annotation.SearchIndexSpecs;
import io.softa.starter.metadata.scanner.annotation.SearchIndexSynthesizer;
import io.softa.starter.metadata.scanner.checker.MetadataAnnotationChecker;
import io.softa.starter.metadata.scanner.diff.DiffEngine;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;

/**
 * Shared read side of the metadata reconciliation algorithm:
 * {@code discover → parse → load → diff}. The four collaborators
 * ({@link AnnotationParser}, {@link DiffEngine}, {@link SysJdbcLoader},
 * classpath discovery) are stateless POJOs that were previously hand-{@code new}ed
 * identically by both {@link MetadataAnnotationScanner} (boot, write side) and
 * {@link MetadataAnnotationChecker} (post-boot,
 * read side). Holding them in one injected bean removes that duplicated wiring and
 * gives both consumers a single, mockable seam (share the
 * algorithm subpackage).
 *
 * <p>This bean is read-only — it never writes {@code sys_*} or runs DDL. The
 * scope-specific logic (which packages are in scope, whether to confine the
 * from-DB set) stays with each caller; this pipeline only provides the
 * scope-agnostic primitives.
 */
@Slf4j
@Component
public class MetadataReadPipeline {

    private final BeanFactory beanFactory;
    private final MetadataProperties properties;
    private final SysJdbcLoader loader;
    private final AnnotationParser parser = new AnnotationParser();
    private final DiffEngine diffEngine = new DiffEngine();

    public MetadataReadPipeline(BeanFactory beanFactory, JdbcTemplate jdbcTemplate,
                                MetadataProperties properties) {
        this.beanFactory = beanFactory;
        this.properties = properties;
        this.loader = new SysJdbcLoader(jdbcTemplate);
    }

    /** All {@code @Model} classes on the application classpath. */
    public Set<Class<?>> discoverModelClasses() {
        return support().findModelClasses();
    }

    /** All {@code @OptionSet} enums on the application classpath. */
    public Set<Class<?>> discoverOptionSetEnums() {
        return support().findOptionSetEnums();
    }

    /**
     * Parse the given models / option-set enums into the from-code state, then append the search
     * indexes derived from {@code @Model(searchName = ...)} — see {@link SearchIndexSynthesizer}.
     *
     * <p>The derivation belongs HERE, not in {@link AnnotationParser} and not in the scanner,
     * because this method is the only seam both {@link MetadataAnnotationScanner} (write lane) and
     * {@link MetadataAnnotationChecker} (read-only production lane) go through. Deriving on one side
     * only would make the two lanes' from-code fingerprints disagree, and the checker would then
     * report permanent drift on every boot. Same reasoning, same layer as
     * {@code ReferenceColumnResolver.stampSysFields}.
     *
     * <p>Because the rows land in the from-code set, they are declared indexes in every downstream
     * sense: they enter the diff, get written to {@code sys_model_index}, are created by convergence,
     * and — critically — join {@code DdlOrchestrator}'s declared-name set, so the undeclared-index
     * DROP never reaps them.
     */
    public AnnotationScanResult parse(Collection<Class<?>> modelClasses,
                                      Collection<Class<?>> optionSetEnums) {
        AnnotationScanResult parsed = parser.parse(modelClasses, optionSetEnums);
        List<SysModelIndex> derived = SearchIndexSynthesizer.derive(
                SearchIndexSpecs.from(parsed.models(), parsed.fields()),
                parsed.modelIndexes().stream().map(SysModelIndex::getIndexName).toList());
        if (derived.isEmpty()) {
            return parsed;
        }
        log.debug("Derived {} search index(es) from @Model(searchName)", derived.size());
        List<SysModelIndex> indexes = new ArrayList<>(parsed.modelIndexes());
        indexes.addAll(derived);
        return new AnnotationScanResult(parsed.models(), parsed.fields(), parsed.optionSets(),
                parsed.optionItems(), indexes, parsed.renames());
    }

    /**
     * Strict load of the current {@code sys_*} state (scanner / write path). A
     * read failure propagates — the caller is about to reconcile writes against
     * this state and must not diff against a fabricated empty baseline.
     */
    public AnnotationScanResult loadCurrentState() {
        return loader.load();
    }

    /**
     * Lenient load (checker / read-only path): a read failure degrades to an
     * empty state with a WARN, surfacing everything as drift.
     */
    public AnnotationScanResult loadCurrentStateLenient() {
        return loader.loadLenient();
    }

    /** Diff from-code against from-db. */
    public SchemaDiff diff(AnnotationScanResult fromCode, AnnotationScanResult fromDb) {
        return diffEngine.diff(fromCode, fromDb);
    }

    /**
     * Discovery roots = the application's own {@code AutoConfigurationPackages}
     * ∪ {@code system.metadata.scan-base-packages} (default {@code io.softa}).
     * The app root alone cannot see framework / starter packages, so without
     * the configured roots the documented starter annotation lane (system
     * models, reference data, framework enums) would be unreachable.
     */
    private ClasspathScannerSupport support() {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        try {
            roots.addAll(AutoConfigurationPackages.get(beanFactory));
        } catch (IllegalStateException e) {
            log.debug("MetadataReadPipeline: no AutoConfigurationPackages registered; "
                    + "falling back to scan-base-packages only");
        }
        roots.addAll(properties.scanBasePackages());
        return new ClasspathScannerSupport(roots);
    }
}
