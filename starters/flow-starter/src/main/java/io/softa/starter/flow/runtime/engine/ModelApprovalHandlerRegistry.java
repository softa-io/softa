package io.softa.starter.flow.runtime.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.base.utils.SFunction;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.spi.impl.OrganizationServiceProperties;

/**
 * The application's {@link ModelApprovalHandler}s, by the model they serve — the models whose records
 * are approved. Two handlers for one model fail the boot: which one a submission reached would depend
 * on bean order. So does a served model whose metadata lacks the approval fields its class inherits
 * from {@link ApprovalModel} — the first submission would otherwise fail on a column that is not there
 * — or whose subject field is not a many-to-one onto the employee model the organization is read from.
 *
 * <p>Handlers are looked up once every singleton exists rather than injected: a business service
 * that is a handler and also submits its records through {@code FlowClient} would otherwise close a
 * construction cycle through this registry.
 */
@Component
public class ModelApprovalHandlerRegistry implements SmartInitializingSingleton {

    /** The subject field of a model whose handler names none. */
    public static final String DEFAULT_SUBJECT_FIELD = "employeeId";

    private final Supplier<List<ModelApprovalHandler<?>>> handlers;
    private final OrganizationServiceProperties organization;
    private volatile Map<String, ModelApprovalHandler<?>> handlersByModel;

    @Autowired
    public ModelApprovalHandlerRegistry(ObjectProvider<ModelApprovalHandler<?>> handlers,
                                        OrganizationServiceProperties organization) {
        this.handlers = () -> handlers.orderedStream().toList();
        this.organization = organization;
    }

    /** A registry over a fixed set of handlers, on the default organization models. */
    public static ModelApprovalHandlerRegistry of(List<? extends ModelApprovalHandler<?>> handlers) {
        return new ModelApprovalHandlerRegistry(List.copyOf(handlers));
    }

    private ModelApprovalHandlerRegistry(List<ModelApprovalHandler<?>> handlers) {
        this.handlers = () -> handlers;
        this.organization = new OrganizationServiceProperties();
    }

    /** Metadata is loaded by now ({@code AppStartup} is a singleton's initialization). */
    @Override
    public void afterSingletonsInstantiated() {
        handlersByModel().forEach((modelName, handler) -> {
            requireApprovalFields(modelName, handler);
            requireSubjectField(modelName, handler);
        });
    }

    /** The models whose records the application approves: those with a handler. */
    public Set<String> modelNames() {
        return Set.copyOf(handlersByModel().keySet());
    }

    /** The handler of {@code modelName}, if the application has one. */
    public Optional<ModelApprovalHandler<?>> find(String modelName) {
        return modelName == null ? Optional.empty() : Optional.ofNullable(handlersByModel().get(modelName));
    }

    /**
     * The handler of {@code modelName}.
     *
     * @throws BusinessException when the application has none: its records cannot be submitted
     */
    public ModelApprovalHandler<?> require(String modelName) {
        return find(modelName).orElseThrow(() -> new BusinessException(
                "Records of {0} cannot be submitted for approval: the application has no approval handler for them.",
                modelName));
    }

    /** The field a handler's records name their subject in: its {@code subject()}, or {@code employeeId}. */
    public static String subjectFieldOf(ModelApprovalHandler<?> handler) {
        SFunction<?, Long> subject = handler.subject();
        return subject == null ? DEFAULT_SUBJECT_FIELD : LambdaUtils.getAttributeName(subject);
    }

    /** The model a handler serves: its entity class's name, as for every entity. */
    public static String modelNameOf(ModelApprovalHandler<?> handler) {
        Class<?> modelClass = handler.modelClass();
        if (modelClass == null) {
            throw new IllegalStateException("Approval handler " + handler.getClass().getName()
                    + " does not name the model it serves");
        }
        return modelClass.getSimpleName();
    }

    private Map<String, ModelApprovalHandler<?>> handlersByModel() {
        Map<String, ModelApprovalHandler<?>> index = handlersByModel;
        if (index == null) {
            synchronized (this) {
                if (handlersByModel == null) {
                    handlersByModel = index(handlers.get());
                }
                index = handlersByModel;
            }
        }
        return index;
    }

    private static Map<String, ModelApprovalHandler<?>> index(List<ModelApprovalHandler<?>> handlers) {
        Map<String, ModelApprovalHandler<?>> byModel = new HashMap<>();
        for (ModelApprovalHandler<?> handler : handlers) {
            String modelName = modelNameOf(handler);
            ModelApprovalHandler<?> previous = byModel.putIfAbsent(modelName, handler);
            if (previous != null) {
                throw new IllegalStateException("Model " + modelName + " has two approval handlers: "
                        + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        return Map.copyOf(byModel);
    }

    private static void requireApprovalFields(String modelName, ModelApprovalHandler<?> handler) {
        if (!ModelManager.existModel(modelName)) {
            throw new IllegalStateException("Approval handler " + handler.getClass().getName()
                    + " serves model " + modelName + ", which has no metadata");
        }
        for (String field : ApprovalModel.APPROVAL_FIELDS) {
            if (!ModelManager.existField(modelName, field)) {
                throw new IllegalStateException("Model " + modelName + " is approved by "
                        + handler.getClass().getName() + " but its metadata has no " + field
                        + " field: reconcile the fields it inherits from ApprovalModel before it boots");
            }
        }
    }

    private void requireSubjectField(String modelName, ModelApprovalHandler<?> handler) {
        String field = subjectFieldOf(handler);
        String employeeModel = organization.getEmployeeModel();
        if (!ModelManager.existField(modelName, field)) {
            throw new IllegalStateException("Model " + modelName + " is approved by " + handler.getClass().getName()
                    + " but has no " + field + " field to name the employee its records are about: give it one,"
                    + " or name another many-to-one onto " + employeeModel + " in the handler's subject()");
        }
        MetaField subject = ModelManager.getModelField(modelName, field);
        if (subject.getFieldType() != FieldType.MANY_TO_ONE || !employeeModel.equals(subject.getRelatedModel())) {
            throw new IllegalStateException("The subject of " + modelName + ", " + field
                    + ", must be a many-to-one field onto " + employeeModel + ": the employee its records are about");
        }
    }
}
