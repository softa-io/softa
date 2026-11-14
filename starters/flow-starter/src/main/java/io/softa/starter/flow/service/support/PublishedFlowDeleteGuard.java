package io.softa.starter.flow.service.support;

import java.io.Serializable;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.starter.flow.entity.FlowDesign;

/**
 * Keeps a published flow from being deleted, whichever way the delete comes — the editor's
 * {@code POST /flow/designs/{id}/delete} or the generic {@code /FlowDesign} API: its revisions, instances and
 * approval history refer to it by id. A flow no longer wanted is disabled instead. A draft that was never
 * published is deleted freely. A system write (no context, or one that skips permission checks) is trusted.
 */
@Component
@Order(20)
public class PublishedFlowDeleteGuard implements ModelWriteValidator {

    static final String PUBLISHED = "A published flow cannot be deleted: its revisions, instances and approval "
            + "history refer to it. Disable it instead.";

    private static final String MODEL = FlowDesign.class.getSimpleName();

    private final ModelService<Serializable> modelService;

    @SuppressWarnings("unchecked")
    public PublishedFlowDeleteGuard(ModelService<?> modelService) {
        this.modelService = (ModelService<Serializable>) modelService;
    }

    @Override
    public boolean supports(String modelName) {
        return MODEL.equals(modelName);
    }

    @Override
    public void validateDelete(String modelName, List<? extends Serializable> ids) {
        if (ids.isEmpty() || !ContextHolder.existContext() || ContextHolder.getContext().isSkipPermissionCheck()) {
            return;
        }
        Filters published = new Filters().in(FlowDesign::getId, List.copyOf(ids)).isSet(FlowDesign::getPublishedRevision);
        if (ContextUtils.withoutPermissionChecks(() -> modelService.count(MODEL, published)) > 0) {
            throw new WriteValidationException(PUBLISHED);
        }
    }
}
