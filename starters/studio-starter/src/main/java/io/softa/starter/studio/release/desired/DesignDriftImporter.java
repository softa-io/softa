package io.softa.starter.studio.release.desired;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Cast;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IDGenerator;
import io.softa.starter.studio.release.dto.DesignAggregate;
import io.softa.starter.studio.release.dto.DesignMetaTables;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * Writes an <b>inverted</b> design↔runtime drift back onto a target env's {@code design_*} rows — the
 * write half of import (Softa connector, full-fidelity) / reverse (JDBC connector, structural-only).
 * <p>
 * The drift is computed as "operations to apply to the runtime to match the env design"
 * ({@link DesignAggregateDiffer}); importing runtime state into design-time flips every operation:
 * <ul>
 *   <li>{@code drift.deletedRows} (runtime has, design doesn't) → CREATE on design using {@code fullRow}
 *       (the runtime values), in the {@link DesignAggregate} parents-first order so a child's parent
 *       exists first, then relinked to that target-env parent by business code
 *       ({@link #relinkImportedChildFk});</li>
 *   <li>{@code drift.updatedRows} → UPDATE on design with the runtime values in
 *       {@code previousValuesForChangedFields}, located by the design row's <b>business key</b>;</li>
 *   <li>{@code drift.createdRows} (design has, runtime doesn't) → DELETE from design located by business
 *       key, in {@link DesignAggregate#deleteOrder()} so children drop before parents.</li>
 * </ul>
 * The env↔env sibling of this writer is {@link DesignEnvMerger}; both take their per-table topology from
 * the {@link DesignAggregate} descriptor and locate / re-parent by business key through the shared
 * {@link DesignEnvRowOps} primitives (no surrogate id threaded across envs). The caller
 * ({@code DesignAppEnvServiceImpl.applyDrift}) runs this under the env mutex and its own transaction.
 */
@Component
public class DesignDriftImporter {

    private final ModelService<Serializable> modelService;

    public DesignDriftImporter(ModelService<Serializable> modelService) {
        this.modelService = modelService;
    }

    /**
     * Apply the inverted drift onto {@code target}'s design rows (see the class doc for the op flip).
     * Creates run parent→child, updates in any order, deletes children→parents.
     */
    public void apply(List<RowChangeDTO> drift, DesignAppEnv target) {
        // The drift diff is a flat row-change list; regroup per design meta-model for the FK-safe order.
        Map<String, ModelChangesDTO> byModel = DesignMetaTables.group(drift).stream()
                .collect(Collectors.toMap(ModelChangesDTO::getModelName, m -> m));

        // Inserts: parent before child — the descriptor's declaration order.
        for (DesignAggregate aggregate : DesignAggregate.values()) {
            ModelChangesDTO changes = byModel.get(aggregate.designName());
            if (changes == null) {
                continue;
            }
            List<Map<String, Object>> toCreate = changes.getDeletedRows().stream()
                    .map(RowChangeDTO::getFullRow)
                    .filter(Objects::nonNull)
                    .map(HashMap::new)
                    .map(row -> stampImportedRow(row, target))
                    .collect(Collectors.toList());
            relinkImportedChildFk(aggregate, toCreate, target);
            if (!toCreate.isEmpty()) {
                modelService.createList(aggregate.designName(), Cast.of(toCreate));
            }
        }

        // Updates: order irrelevant. Locate each design row by its business key — the runtime values to
        // write live in previousValuesForChangedFields; fullRow carries the design row's current key.
        for (DesignAggregate aggregate : DesignAggregate.values()) {
            ModelChangesDTO changes = byModel.get(aggregate.designName());
            if (changes == null || changes.getUpdatedRows().isEmpty()) {
                continue;
            }
            Map<String, Long> idByBizKey = designRowIdsByBizKey(aggregate, target);
            List<Map<String, Object>> toUpdate = new ArrayList<>();
            for (RowChangeDTO row : changes.getUpdatedRows()) {
                Long id = idByBizKey.get(DesignEnvRowOps.bizKey(aggregate.bizKeyAttrs(), row.getFullRow()));
                if (id == null) {
                    continue;   // design row absent (should not happen for an UPDATE) — skip rather than misroute
                }
                Map<String, Object> payload = new HashMap<>(row.getPreviousValuesForChangedFields());
                payload.put(ModelConstant.ID, id);
                toUpdate.add(payload);
            }
            if (!toUpdate.isEmpty()) {
                modelService.updateList(aggregate.designName(), toUpdate);
            }
        }

        // Deletes: children before parents. Located by business key.
        for (DesignAggregate aggregate : DesignAggregate.deleteOrder()) {
            ModelChangesDTO changes = byModel.get(aggregate.designName());
            if (changes == null || changes.getCreatedRows().isEmpty()) {
                continue;
            }
            Map<String, Long> idByBizKey = designRowIdsByBizKey(aggregate, target);
            List<Serializable> ids = new ArrayList<>();
            for (RowChangeDTO row : changes.getCreatedRows()) {
                Long id = idByBizKey.get(DesignEnvRowOps.bizKey(aggregate.bizKeyAttrs(), row.getFullRow()));
                if (id != null) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                modelService.deleteByIds(aggregate.designName(), Cast.of(ids));
            }
        }
    }

    /** This env's rows of one meta-table indexed by their (env-scoped) business key → surrogate id. */
    private Map<String, Long> designRowIdsByBizKey(DesignAggregate aggregate, DesignAppEnv target) {
        return DesignEnvRowOps.indexByKey(
                modelService.searchList(aggregate.designName(),
                        new FlexQuery(new Filters().eq(DesignEnvRowOps.ENV_ID, target.getId()))),
                aggregate.bizKeyAttrs());
    }

    /**
     * Stamp a runtime row being imported into the target env's design. The runtime {@code sys_*} catalog
     * carries business values only — no per-env identity — so scope the row to the target env
     * ({@code appId}/{@code envId}) and mint a fresh design surrogate id (dropping the runtime's).
     */
    private static Map<String, Object> stampImportedRow(Map<String, Object> row, DesignAppEnv target) {
        row.put("appId", target.getAppId());
        row.put(DesignEnvRowOps.ENV_ID, target.getId());
        row.put(ModelConstant.ID, IDGenerator.generateLongId());
        return row;
    }

    /**
     * Relink imported child rows to their parent in the TARGET env. A runtime {@code sys_*} child row
     * carries its parent's business code but a surrogate FK in the <b>runtime's</b> id-space, foreign to
     * this design env — so the FK is <b>unconditionally overwritten</b> with the target-env parent id
     * that owns the same business code (never "fill if null"). Safe here because the insert loop follows
     * the descriptor's parents-first order, so every parent already exists in the target env. Roots have
     * no parent FK — no-op.
     */
    private void relinkImportedChildFk(DesignAggregate aggregate, List<Map<String, Object>> rows,
                                       DesignAppEnv target) {
        DesignAggregate parent = aggregate.parent();
        if (parent == null || rows.isEmpty()) {
            return;
        }
        Map<String, Long> parentIdByCode = new HashMap<>();
        for (Map<String, Object> parentRow : modelService.searchList(parent.designName(),
                new FlexQuery(new Filters().eq(DesignEnvRowOps.ENV_ID, target.getId())))) {
            Object code = parentRow.get(aggregate.parentCodeAttr());
            Long id = DesignEnvRowOps.asLong(parentRow.get(ModelConstant.ID));
            if (code != null && id != null) {
                parentIdByCode.put(String.valueOf(code), id);
            }
        }
        DesignEnvRowOps.relinkChildFk(rows, aggregate.parentFkAttr(), aggregate.parentCodeAttr(), parentIdByCode);
    }
}
