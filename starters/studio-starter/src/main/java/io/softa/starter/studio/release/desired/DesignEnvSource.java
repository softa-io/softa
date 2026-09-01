package io.softa.starter.studio.release.desired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignModelIndex;
import io.softa.starter.studio.meta.entity.DesignOptionItem;
import io.softa.starter.studio.meta.entity.DesignOptionSet;

/**
 * Sources the design (desired) state for a publish/merge from the LIVE per-env design: an
 * env's current {@code design_*} rows ARE the desired state, and a publish converges that env's runtime
 * to them. There is no version or snapshot baseline — the live rows are the whole truth.
 *
 * <p>Returns camelCase attribute maps scoped to a single {@code (appId, envId)}, ready to feed
 * {@link DesiredStateComparator}.
 */
@Component
public class DesignEnvSource {

    private static final String APP_ID = LambdaUtils.getAttributeName(DesignModel::getAppId);
    private static final String ENV_ID = LambdaUtils.getAttributeName(DesignModel::getEnvId);

    private final ModelService<Long> modelService;

    public DesignEnvSource(ModelService<Long> modelService) {
        this.modelService = modelService;
    }

    /**
     * Load one env's full design state (per-env design): the five meta-tables filtered to
     * {@code (appId, envId)}. The env-scoped source for {@code publish(envId)} (design↔runtime deploy)
     * and env↔env merge.
     */
    public DesignRows load(Long appId, Long envId) {
        Assert.notNull(appId, "appId must not be null");
        Assert.notNull(envId, "envId must not be null");
        List<Map<String, Object>> models = search(DesignModel.class.getSimpleName(), appId, envId);
        List<Map<String, Object>> fields = search(DesignField.class.getSimpleName(), appId, envId);
        List<Map<String, Object>> indexes =
                new ArrayList<>(search(DesignModelIndex.class.getSimpleName(), appId, envId));
        // Search indexes are derived, never stored: they follow searchName, so persisting them
        // would make them separately editable and the two would fight. The annotation lane derives
        // the identical rows at the same point in its own read path — see DesignSearchIndexSpecs
        // for why a lane that skipped this would delete the other lane's indexes on every deploy.
        indexes.addAll(DesignSearchIndexSpecs.derive(models, fields, indexes));
        return new DesignRows(
                models,
                fields,
                indexes,
                search(DesignOptionSet.class.getSimpleName(), appId, envId),
                search(DesignOptionItem.class.getSimpleName(), appId, envId));
    }

    private List<Map<String, Object>> search(String modelName, Long appId, Long envId) {
        Filters filters = new Filters().eq(APP_ID, appId).eq(ENV_ID, envId);
        return modelService.searchList(modelName, new FlexQuery(filters));
    }
}
