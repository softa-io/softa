package io.softa.starter.studio.release.desired;

import static io.softa.starter.studio.release.desired.DesignEnvRowOps.ENV_ID;
import static io.softa.starter.studio.release.desired.DesignEnvRowOps.ID;
import static io.softa.starter.studio.release.desired.DesignEnvRowOps.asLong;
import static io.softa.starter.studio.release.desired.DesignEnvRowOps.prepareClone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignModelIndex;
import io.softa.starter.studio.meta.entity.DesignOptionItem;
import io.softa.starter.studio.meta.entity.DesignOptionSet;
import io.softa.starter.studio.release.dto.DesignAggregate;

/**
 * Clones one environment's full {@code design_*} aggregate set into another environment
 * (per-env design). This is the reusable per-env materialization primitive:
 * <ul>
 *   <li><b>Phase 1b seeding</b> — after the V16 migration assigns the existing single
 *       workspace to each app's canonical active env, this seeds the <em>other</em> active
 *       envs from that canonical one, so every env owns a full design set.</li>
 *   <li><b>Future "initialize a new env from a source env"</b> — the pure-peer model
 *       creates a new env by cloning an existing one.</li>
 *   <li><b>Phase 3 merge</b> reuses the same aggregate-level FK-remap logic, wrapped in a
 *       business-key-keyed diff so only differing aggregates are overwritten.</li>
 * </ul>
 *
 * <p>Why this lives in code, not the V16 SQL migration: a faithful clone must mint a fresh
 * per-env surrogate {@code id} for every row (CosID {@code DISTRIBUTED_LONG}, assigned by the
 * create pipeline when {@code id} is absent) and <b>remap the parent FK</b>
 * ({@code design_field.modelId} / {@code design_model_index.modelId} →
 * {@code design_option_item.optionSetId}) onto the cloned parent's new id. Hand-writing that in
 * pure SQL is unsafe; here the framework assigns ids and we thread the parent's new id into its
 * children. The business-key columns ({@code modelName} / {@code fieldName} / …) are copied verbatim,
 * so every env's copy of one logical entity shares the same business key — the cross-env correlation
 * key (no logicalId).
 *
 * <p>Runs in the caller's transaction. Idempotency (don't re-seed a non-empty target) is the
 * caller's responsibility — see {@code DesignAppEnvService.seedFromSource}.
 */
@Component
public class DesignEnvCloner {

    private static final String MODEL = DesignModel.class.getSimpleName();
    private static final String FIELD = DesignField.class.getSimpleName();
    private static final String INDEX = DesignModelIndex.class.getSimpleName();
    private static final String OPTION_SET = DesignOptionSet.class.getSimpleName();
    private static final String OPTION_ITEM = DesignOptionItem.class.getSimpleName();

    // ID / ENV_ID are shared via DesignEnvRowOps (static-imported); the child→parent FK attrs come from
    // the DesignAggregate descriptor. Only APP_ID is Cloner-local.
    private static final String APP_ID = LambdaUtils.getAttributeName(DesignModel::getAppId);
    private static final String MODEL_ID = DesignAggregate.FIELD.parentFkAttr();
    private static final String OPTION_SET_ID = DesignAggregate.OPTION_ITEM.parentFkAttr();

    private final ModelService<Long> modelService;

    public DesignEnvCloner(ModelService<Long> modelService) {
        this.modelService = modelService;
    }

    /**
     * Clone every {@code design_*} aggregate of {@code appId} from {@code sourceEnvId} into
     * {@code targetEnvId}, minting fresh ids and remapping parent FKs. The business-key columns are
     * copied verbatim, so the clone correlates to its source across envs by business key.
     *
     * @return number of rows created
     */
    public int cloneEnv(Long appId, Long sourceEnvId, Long targetEnvId) {
        return materialize(loadEnvRows(appId, sourceEnvId), targetEnvId);
    }

    /**
     * Materialize {@code source}'s aggregates into {@code targetEnvId}: for each parent (model /
     * option set) mint a fresh surrogate id ({@link DesignEnvRowOps#prepareClone} drops the source id,
     * the create pipeline assigns a new one) and re-parent its children onto that new id; the
     * business-key columns are copied verbatim. <b>FK-remapping, not id-preserving</b> — so it is correct
     * regardless of {@code system.enable-insert-id} (which, when off — the default — regenerates every
     * id on insert, so a verbatim id-preserving re-insert would silently decouple child FKs).
     *
     * @return number of rows created
     */
    private int materialize(DesignRows source, Long targetEnvId) {
        Map<Long, List<Map<String, Object>>> fieldsByModel = groupByParent(source.fields(), MODEL_ID);
        Map<Long, List<Map<String, Object>>> indexesByModel = groupByParent(source.indexes(), MODEL_ID);
        Map<Long, List<Map<String, Object>>> itemsBySet = groupByParent(source.items(), OPTION_SET_ID);

        int created = 0;

        // Model aggregates: model + its fields + its indexes (children re-parented to the new model id).
        for (Map<String, Object> srcModel : source.models()) {
            Long srcModelId = asLong(srcModel.get(ID));
            Long newModelId = modelService.createOne(MODEL, prepareClone(srcModel, targetEnvId));
            created++;
            created += createChildren(FIELD, fieldsByModel.get(srcModelId), targetEnvId, MODEL_ID, newModelId);
            created += createChildren(INDEX, indexesByModel.get(srcModelId), targetEnvId, MODEL_ID, newModelId);
        }

        // Option-set aggregates: option set + its items (re-parented to the new option-set id).
        for (Map<String, Object> srcSet : source.optionSets()) {
            Long srcSetId = asLong(srcSet.get(ID));
            Long newSetId = modelService.createOne(OPTION_SET, prepareClone(srcSet, targetEnvId));
            created++;
            created += createChildren(OPTION_ITEM, itemsBySet.get(srcSetId), targetEnvId, OPTION_SET_ID, newSetId);
        }

        return created;
    }

    /** Load one env's full design aggregate set as {@link DesignRows}. */
    private DesignRows loadEnvRows(Long appId, Long envId) {
        return new DesignRows(
                loadByEnv(MODEL, appId, envId),
                loadByEnv(FIELD, appId, envId),
                loadByEnv(INDEX, appId, envId),
                loadByEnv(OPTION_SET, appId, envId),
                loadByEnv(OPTION_ITEM, appId, envId));
    }

    /** Count an env's design models — used by callers to enforce the "don't re-seed" idempotency guard. */
    public int countModels(Long appId, Long envId) {
        return loadByEnv(MODEL, appId, envId).size();
    }

    /**
     * Replace env {@code envId}'s entire per-env design with {@code snapshot} (restore):
     * delete the current {@code design_*} rows (children before parents) and re-materialize the snapshot
     * via {@link #materialize} — fresh surrogate ids + remapped parent FKs, business-key columns carried.
     *
     * <p><b>Not</b> a verbatim id-preserving re-insert: the create pipeline regenerates ids when
     * {@code system.enable-insert-id} is off (the default), so id preservation is not available — but a
     * design's identity is its <b>business key</b> (carried) + structure (FK-remapped), not its per-env
     * surrogate ids, so an FK-remapped re-materialization is a faithful restore.
     *
     * <p>Own transaction (delete + insert atomic, so a failed restore never leaves a half-deleted
     * design); the caller holds the env mutex.
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceEnvDesign(Long envId, DesignRows snapshot) {
        Filters envScope = new Filters().eq(ENV_ID, envId);
        // Delete children before parents.
        for (DesignAggregate aggregate : DesignAggregate.deleteOrder()) {
            String model = aggregate.designName();
            modelService.deleteByFilters(model, envScope);
        }
        // Re-materialize: fresh ids + remapped FKs + carried business key (flag-independent).
        materialize(snapshot, envId);
    }

    // ----------------------------------------------------------------- internals

    private int createChildren(String modelName, List<Map<String, Object>> srcChildren,
                               Long targetEnvId, String parentFk, Long newParentId) {
        if (srcChildren == null || srcChildren.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> clones = new ArrayList<>(srcChildren.size());
        for (Map<String, Object> child : srcChildren) {
            Map<String, Object> clone = prepareClone(child, targetEnvId);
            clone.put(parentFk, newParentId);   // re-parent onto the cloned parent's new id
            clones.add(clone);
        }
        modelService.createList(modelName, clones);
        return clones.size();
    }

    private List<Map<String, Object>> loadByEnv(String modelName, Long appId, Long envId) {
        return modelService.searchList(modelName,
                new FlexQuery(new Filters().eq(APP_ID, appId).eq(ENV_ID, envId)));
    }

    private Map<Long, List<Map<String, Object>>> groupByParent(List<Map<String, Object>> rows, String parentFk) {
        return rows.stream()
                .filter(r -> r.get(parentFk) != null)
                .collect(Collectors.groupingBy(r -> asLong(r.get(parentFk)),
                        LinkedHashMap::new, Collectors.toList()));
    }
}
