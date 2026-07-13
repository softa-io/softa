package io.softa.framework.orm.service.versioning;

import org.springframework.stereotype.Component;

import io.softa.framework.orm.meta.ModelManager;

/**
 * Resolves the {@link VersioningStrategy} for a model — the single place the
 * timeline/non-timeline distinction is made. Callers invoke the strategy unconditionally,
 * so a new read or write path cannot forget the timeline handling: there is no branch to
 * forget, only this resolver.
 */
@Component
public class VersioningStrategyResolver {

    private final IdentityStrategy<?> identityStrategy;
    private final TimelineStrategy<?> timelineStrategy;

    public VersioningStrategyResolver(IdentityStrategy<?> identityStrategy, TimelineStrategy<?> timelineStrategy) {
        this.identityStrategy = identityStrategy;
        this.timelineStrategy = timelineStrategy;
    }

    /**
     * Resolve the versioning strategy for the given model.
     *
     * @param modelName model name
     * @return the timeline strategy for timeline models; the identity (no-op) strategy otherwise
     */
    public VersioningStrategy of(String modelName) {
        return ModelManager.isTimelineModel(modelName) ? timelineStrategy : identityStrategy;
    }
}
