package io.softa.starter.metadata.service.impl;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysView;
import io.softa.starter.metadata.entity.SysViewDefault;
import io.softa.starter.metadata.service.SysViewDefaultService;
import io.softa.starter.metadata.service.SysViewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * SysView Model Service Implementation
 */
@Service
public class SysViewServiceImpl extends EntityServiceImpl<SysView, Long> implements SysViewService {

    @Autowired
    private SysViewDefaultService viewDefaultService;

    /**
     * Set the default view for current user.
     * @param modelName Model name
     * @param viewId View ID
     * @return Boolean
     */
    @Override
    public boolean setDefaultView(String modelName, Long viewId) {
        String currentUserId = ContextHolder.getContext().getUserId();
        Filters personalFilters = new Filters().eq(SysViewDefault::getModelName, modelName)
                .eq(SysViewDefault::getCreatedId, currentUserId);
        Optional<SysViewDefault> optionalDefaultView = viewDefaultService.searchOne(new FlexQuery(personalFilters));
        if (optionalDefaultView.isPresent()) {
            SysViewDefault defaultView = optionalDefaultView.get();
            defaultView.setViewId(viewId);
            return viewDefaultService.updateOne(defaultView);
        } else {
            SysViewDefault defaultView = new SysViewDefault();
            defaultView.setModelName(modelName);
            defaultView.setViewId(viewId);
            defaultView.setCreatedId(currentUserId);
            return viewDefaultService.createOne(defaultView) != null;
        }
    }

    /**
     * Get the views of the specified model, including public views and personal views
     * @param modelName Model name
     * @return List of views
     */
    @Override
    public List<SysView> getModelViews(String modelName) {
        String currentUserId = ContextHolder.getContext().getUserId();
        // Public views first, personal views second, and sorted by sequence. Search filters:
        // model_name={modelName} and (public_view=true or created_id={currentUserId}) ORDER BY public_view DESC, sequence
        Filters viewFilters = new Filters().eq(SysView::getModelName, modelName)
                .and(Filters.or().eq(SysView::getPublicView, true)
                        .eq(SysView::getCreatedId, currentUserId));
        Orders orders = Orders.ofDesc(SysView::getPublicView).addAsc(SysView::getSequence);
        List<SysView> views = this.searchList(new FlexQuery(viewFilters, orders));
        // Get the default views of current user
        Filters personalFilters = new Filters().eq(SysViewDefault::getModelName, modelName)
                .eq(SysViewDefault::getCreatedId, currentUserId);
        Optional<SysViewDefault> optionalDefaultView = viewDefaultService.searchOne(new FlexQuery(personalFilters));
        optionalDefaultView.ifPresent(sysViewDefault ->
                views.forEach(v -> v.setDefaultView(v.getId().equals(sysViewDefault.getViewId()))));
        return views;
    }
}