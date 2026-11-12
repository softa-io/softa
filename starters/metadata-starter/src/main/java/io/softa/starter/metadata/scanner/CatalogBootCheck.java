package io.softa.starter.metadata.scanner;

import java.util.Collection;
import java.util.List;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaIndex;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.metadata.ddl.ReferenceColumnResolver;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;

/**
 * The checks {@link ModelManager} makes when it loads the {@code sys_*} catalog at boot, run over
 * the catalog the annotations on the classpath declare — with no database and no Spring context.
 *
 * <p>Those checks read the whole catalog: a relation needs the model it points at, a
 * {@code searchName} the type of every field it names. A mistake can therefore pass each module's
 * own tests and surface only when an application loads every module together, at boot — softa
 * 3.0.1 widened {@code InboxNotification.content} to Text while the inbox still searched by it, and
 * every application on that release refused to start. A test of the application calls
 * {@link #validate} with the packages it discovers models under, and the build fails instead.
 *
 * <p>The catalog checked is the one a {@code scanner-scope: ["*"]} boot writes: every model and
 * option set under the packages, parsed as the scanner parses them
 * ({@link MetadataReadPipeline#parseFromCode}), each TO_ONE column's type stamped
 * ({@link ReferenceColumnResolver}), and each row mapped onto the metadata objects as the boot read
 * maps it. Out of reach: rows no class declares (Studio no-code and seed-authored models), rows an
 * environment still holds from older code, and the boot steps outside {@link ModelManager}.
 */
public final class CatalogBootCheck {

    private CatalogBootCheck() {
    }

    /**
     * @param basePackages the packages models and option sets are discovered under: the
     *                     application's own and {@code system.metadata.scan-base-packages}
     *                     (default {@code io.softa})
     * @param multiTenancy the deployment's {@code system.enable-multi-tenancy}. Some checks apply
     *                     only with it on, such as the refusal of a CASCADE from a shared model onto
     *                     a multi-tenant one.
     */
    public static void validate(Collection<String> basePackages, boolean multiTenancy) {
        ClasspathScannerSupport classpath = new ClasspathScannerSupport(basePackages);
        Collection<Class<?>> modelClasses = classpath.findModelClasses();
        Assert.notEmpty(modelClasses, "No @Model class under {0}: there is nothing to check.", basePackages);
        validate(modelClasses, classpath.findOptionSetEnums(), multiTenancy);
    }

    /** {@link #validate(Collection, boolean)} over classes already found. */
    static void validate(Collection<Class<?>> modelClasses, Collection<Class<?>> optionSetEnums,
                         boolean multiTenancy) {
        AnnotationScanResult catalog = MetadataReadPipeline.parseFromCode(modelClasses, optionSetEnums);
        ReferenceColumnResolver.stampSysFields(catalog.fields());
        // The checks read the flag from SystemConfig.env, as at boot; set it for the length of the
        // check and put back whatever was there.
        SystemConfig previous = SystemConfig.env;
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(multiTenancy);
        SystemConfig.env = config;
        try {
            ModelManager.validateCatalog(asRead(catalog.models(), MetaModel.class),
                    asRead(catalog.fields(), MetaField.class), asRead(catalog.modelIndexes(), MetaIndex.class));
        } finally {
            SystemConfig.env = previous;
        }
    }

    /** Rows as the boot read hands them to ModelManager: matched by property name, the rest dropped. */
    private static <T> List<T> asRead(List<?> rows, Class<T> metaClass) {
        return BeanTool.originalMapListToObjects(BeanTool.objectsToMapList(rows), metaClass, true);
    }
}
