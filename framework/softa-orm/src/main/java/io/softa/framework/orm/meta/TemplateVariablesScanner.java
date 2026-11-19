package io.softa.framework.orm.meta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import io.softa.framework.base.annotation.TemplateVariables;

/**
 * Finds every {@link TemplateVariables} record on the classpath at startup and hands them to
 * {@link TemplateVariablesRegistry}.
 *
 * <p>Looks under the framework's own namespace, the application's packages, and the roots the
 * application already names for discovering its {@code @Model} classes
 * ({@code system.metadata.scan-base-packages}) — the same answer to "where does this application
 * keep its declarations", so a module outside the application class's own package is found without
 * a second list to keep. It lives here, not with the {@code @Model} scanner in metadata-starter,
 * because the modules that declare and read these sets (message-starter and the starters that send
 * through it) do not depend on that one.
 *
 * <p>A set that cannot be registered fails this bean, and with it the application's start: a
 * duplicate name would otherwise decide by scan order which set a template is checked against.
 */
@Slf4j
@Component
public class TemplateVariablesScanner implements InitializingBean {

    /** The framework and its starters; the application's own packages are added to it. */
    static final String FRAMEWORK_PACKAGE = "io.softa";

    /** The classpath roots an application lists for discovering its annotated classes. */
    static final String SCAN_BASE_PACKAGES = "system.metadata.scan-base-packages";

    private final BeanFactory beanFactory;

    private final Environment environment;

    public TemplateVariablesScanner(BeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> packages = new LinkedHashSet<>();
        packages.add(FRAMEWORK_PACKAGE);
        try {
            packages.addAll(AutoConfigurationPackages.get(beanFactory));
        } catch (IllegalStateException e) {
            log.debug("TemplateVariablesScanner: no application packages registered; scanning {} only",
                    FRAMEWORK_PACKAGE);
        }
        packages.addAll(Binder.get(environment).bind(SCAN_BASE_PACKAGES, Bindable.listOf(String.class))
                .orElse(List.of()));
        Set<Class<?>> found = scan(packages);
        TemplateVariablesRegistry.register(found);
        log.info("TemplateVariablesScanner: registered {} template variable set(s)", found.size());
    }

    /**
     * Every class carrying {@link TemplateVariables} under {@code packages}. Public for a test that
     * registers an application's sets the way its boot does, to catch a duplicate name in CI.
     */
    public static Set<Class<?>> scan(Set<String> packages) {
        // No default filters: these are plain records, not Spring components.
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        // A record nested in the class that fills it is as good as a top-level one.
                        return definition.getMetadata().isIndependent();
                    }
                };
        provider.addIncludeFilter(new AnnotationTypeFilter(TemplateVariables.class));
        Set<Class<?>> found = new LinkedHashSet<>();
        List<String> unloadable = new ArrayList<>();
        for (String basePackage : packages) {
            provider.findCandidateComponents(basePackage).forEach(definition -> {
                String className = definition.getBeanClassName();
                if (className == null) {
                    return;
                }
                try {
                    found.add(Class.forName(className, false, ClassUtils.getDefaultClassLoader()));
                } catch (ClassNotFoundException | LinkageError e) {
                    unloadable.add(className);
                }
            });
        }
        if (!unloadable.isEmpty()) {
            // Not skipped: a set that silently goes missing turns every template written against
            // it into one that cannot be saved or sent.
            throw new IllegalStateException("Cannot load @TemplateVariables class(es): " + unloadable);
        }
        return found;
    }
}
