package io.softa.starter.studio.release.service.impl;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.entity.DesignAppEnvSnapshot;
import io.softa.starter.studio.release.service.DesignAppEnvService;
import io.softa.starter.studio.release.service.DesignAppEnvSnapshotService;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.metadata.service.MetadataService;

/**
 * DesignAppEnv Model Service Implementation.
 * <p>
 * Provides snapshot management and design-vs-runtime comparison.
 * <p>
 * Snapshot strategy: each env keeps exactly one snapshot (OneToOne) representing the expected
 * runtime state after the latest successful deployment. The snapshot is built incrementally:
 * {@code currentSnapshot = previousSnapshot + mergedChanges}.
 */
@Service
public class DesignAppEnvServiceImpl extends EntityServiceImpl<DesignAppEnv, Long> implements DesignAppEnvService {

    @Autowired
    private DesignAppEnvSnapshotService snapshotService;

    @Autowired
    private MetadataService metadataService;

    @Autowired
    private RemoteApiClient remoteApiClient;

    @Autowired
    private Environment environment;

    /**
     * Take a snapshot of the expected runtime metadata state for the given environment.
     * <p>
     * Loads the previous snapshot (empty for first deployment), then applies the
     * {@code mergedChanges} (CREATE / UPDATE / DELETE) on top to produce the new full state.
     * Uses the synchronized primary id as row identity for applying changes.
     *
     * @param envId         Environment ID
     * @param deploymentId  Deployment ID that produced this snapshot
     * @param mergedChanges the merged version changes that were deployed
     */
    @Override
    public void takeSnapshot(Long envId, Long deploymentId, List<ModelChangesDTO> mergedChanges) {
        DesignAppEnv appEnv = this.getById(envId)
                .orElseThrow(() -> new IllegalArgumentException("Environment does not exist! {0}", envId));

        // Load previous snapshot as baseline (empty map for first deployment)
        DesignAppEnvSnapshot existingSnapshot = findSnapshotByEnvId(envId);
        Map<String, List<Map<String, Object>>> baseline;
        if (existingSnapshot != null && existingSnapshot.getSnapshot() != null) {
            baseline = JsonUtils.jsonNodeToObject(existingSnapshot.getSnapshot(), new TypeReference<>() {});
        } else {
            baseline = new LinkedHashMap<>();
        }

        // Apply mergedChanges on top of the baseline
        applyChangesToBaseline(baseline, mergedChanges);

        // Upsert snapshot (OneToOne with env)
        if (existingSnapshot == null) {
            existingSnapshot = new DesignAppEnvSnapshot();
            existingSnapshot.setAppId(appEnv.getAppId());
            existingSnapshot.setEnvId(envId);
            existingSnapshot.setDeploymentId(deploymentId);
            existingSnapshot.setSnapshot(JsonUtils.objectToJsonNode(baseline));
            snapshotService.createOne(existingSnapshot);
        } else {
            existingSnapshot.setDeploymentId(deploymentId);
            existingSnapshot.setSnapshot(JsonUtils.objectToJsonNode(baseline));
            snapshotService.updateOne(existingSnapshot);
        }
    }

    /**
     * Compare the design-time snapshot with the actual runtime metadata.
     * <p>
     * For each version-controlled model, loads the snapshot rows and the runtime rows,
     * matches them by primary id, and produces a diff (CREATE / UPDATE / DELETE).
     *
     * @param envId Environment ID
     * @return List of model changes representing drift between snapshot and runtime
     */
    @Override
    public List<ModelChangesDTO> compareDesignWithRuntime(Long envId) {
        DesignAppEnv appEnv = this.getById(envId)
                .orElseThrow(() -> new IllegalArgumentException("Environment does not exist! {0}", envId));

        DesignAppEnvSnapshot snapshot = findSnapshotByEnvId(envId);
        Assert.notNull(snapshot, "No snapshot found for environment {0}. Deploy first to create a snapshot.", envId);

        Map<String, List<Map<String, Object>>> snapshotData = JsonUtils.jsonNodeToObject(
                snapshot.getSnapshot(), new TypeReference<>() {});

        List<ModelChangesDTO> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : MetadataConstant.VERSION_CONTROL_MODELS.entrySet()) {
            String designModel = entry.getKey();
            String runtimeModel = entry.getValue();

            List<Map<String, Object>> snapshotRows = snapshotData.getOrDefault(designModel, Collections.emptyList());
            List<Map<String, Object>> runtimeRows = fetchRuntimeData(runtimeModel, appEnv);

            ModelChangesDTO diff = diffSnapshotVsRuntime(designModel, runtimeModel, snapshotRows, runtimeRows);
            if (diff != null) {
                result.add(diff);
            }
        }
        return result;
    }

    // ======================== Snapshot building ========================

    /**
     * Apply merged deployment changes onto the baseline snapshot (in-place mutation).
     * <p>
     * For each model in {@code mergedChanges}:
     * <ul>
     *   <li>CREATE rows → add to baseline by id</li>
     *   <li>UPDATE rows → overwrite baseline row by id using {@code currentData}</li>
     *   <li>DELETE rows → remove from baseline by id</li>
     * </ul>
     *
     * @param baseline       mutable map: designModelName → list of row data
     * @param mergedChanges  the deployment's merged changes
     */
    private void applyChangesToBaseline(Map<String, List<Map<String, Object>>> baseline,
                                        List<ModelChangesDTO> mergedChanges) {
        if (mergedChanges == null) {
            return;
        }
        for (ModelChangesDTO modelChanges : mergedChanges) {
            String designModel = modelChanges.getModelName();
            List<Map<String, Object>> baselineRows = baseline.computeIfAbsent(designModel, k -> new ArrayList<>());
            Map<Long, Map<String, Object>> baselineById = indexById(baselineRows);

            for (RowChangeDTO created : modelChanges.getCreatedRows()) {
                upsertBaselineRow(baselineById, created);
            }

            for (RowChangeDTO updated : modelChanges.getUpdatedRows()) {
                upsertBaselineRow(baselineById, updated);
            }

            for (RowChangeDTO deleted : modelChanges.getDeletedRows()) {
                baselineById.remove(extractRowId(deleted));
            }

            baseline.put(designModel, new ArrayList<>(baselineById.values()));
        }
    }

    // ======================== Snapshot comparison ========================

    /**
     * Find the existing snapshot for an environment (OneToOne).
     */
    private DesignAppEnvSnapshot findSnapshotByEnvId(Long envId) {
        Filters filters = new Filters().eq("envId", envId);
        return snapshotService.searchOne(new FlexQuery(filters)).orElse(null);
    }

    /**
     * Fetch runtime metadata — local if same profile, remote via RPC otherwise.
     */
    private List<Map<String, Object>> fetchRuntimeData(String runtimeModel, DesignAppEnv appEnv) {
        String envType = appEnv.getEnvType().name().toLowerCase();
        if (Arrays.asList(environment.getActiveProfiles()).contains(envType)) {
            return metadataService.exportRuntimeMetadata(runtimeModel);
        }
        return remoteApiClient.fetchRuntimeMetadata(appEnv, runtimeModel);
    }

    /**
     * Compute the diff between snapshot rows and runtime rows for a single model.
     * Uses primary id to match rows between snapshot and runtime.
     * <p>
     * <ul>
     *   <li>CREATE — snapshot row with no matching runtime row (missing in runtime)</li>
     *   <li>UPDATE — matched rows where business field values differ</li>
     *   <li>DELETE — runtime row with no matching snapshot row (extra in runtime)</li>
     * </ul>
     */
    private ModelChangesDTO diffSnapshotVsRuntime(String designModel, String runtimeModel,
                                                   List<Map<String, Object>> snapshotRows,
                                                   List<Map<String, Object>> runtimeRows) {
        Set<String> compareFields = getComparableFields(designModel, runtimeModel);

        Map<Long, Map<String, Object>> runtimeById = indexById(runtimeRows);
        Set<Long> matchedRuntimeIds = new HashSet<>();

        ModelChangesDTO modelChangesDTO = new ModelChangesDTO(designModel);

        for (Map<String, Object> snapshotRow : snapshotRows) {
            Long rowId = extractRowId(snapshotRow);
            Map<String, Object> runtimeRow = runtimeById.get(rowId);

            if (runtimeRow == null) {
                modelChangesDTO.addCreatedRow(toRowChangeDTO(designModel, AccessType.CREATE, snapshotRow));
            } else {
                matchedRuntimeIds.add(rowId);
                Map<String, Object> diffFields = compareFieldValues(snapshotRow, runtimeRow, compareFields);
                if (!diffFields.isEmpty()) {
                    RowChangeDTO rowChangeDTO = toRowChangeDTO(designModel, AccessType.UPDATE, snapshotRow);
                    rowChangeDTO.setDataBeforeChange(extractFields(runtimeRow, diffFields.keySet()));
                    rowChangeDTO.setDataAfterChange(extractFields(snapshotRow, diffFields.keySet()));
                    modelChangesDTO.addUpdatedRow(rowChangeDTO);
                }
            }
        }

        for (Map<String, Object> runtimeRow : runtimeRows) {
            Long rowId = extractRowId(runtimeRow);
            if (!matchedRuntimeIds.contains(rowId)) {
                modelChangesDTO.addDeletedRow(toRowChangeDTO(designModel, AccessType.DELETE, runtimeRow));
            }
        }

        if (modelChangesDTO.getCreatedRows().isEmpty() && modelChangesDTO.getUpdatedRows().isEmpty()
                && modelChangesDTO.getDeletedRows().isEmpty()) {
            return null;
        }
        return modelChangesDTO;
    }

    // ======================== Utility methods ========================

    /**
     * Get fields suitable for comparison: the intersection of design and runtime model fields,
     * excluding identity, audit, and system fields.
     */
    private static Set<String> getComparableFields(String designModel, String runtimeModel) {
        Set<String> designFields = ModelManager.getModelFieldsWithoutXToMany(designModel);
        Set<String> runtimeFields = ModelManager.getModelFieldsWithoutXToMany(runtimeModel);
        Set<String> common = new HashSet<>(designFields);
        common.retainAll(runtimeFields);
        common.removeAll(ModelConstant.AUDIT_FIELDS);
        common.removeAll(Set.of(ModelConstant.ID, ModelConstant.EXTERNAL_ID, ModelConstant.VERSION));
        return common;
    }

    private static Map<Long, Map<String, Object>> indexById(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            index.put(extractRowId(row), new HashMap<>(row));
        }
        return index;
    }

    private static void upsertBaselineRow(Map<Long, Map<String, Object>> baselineById, RowChangeDTO rowChangeDTO) {
        Map<String, Object> currentData = rowChangeDTO.getCurrentData();
        if (currentData != null) {
            baselineById.put(extractRowId(rowChangeDTO), new HashMap<>(currentData));
        }
    }

    private static Long extractRowId(RowChangeDTO rowChangeDTO) {
        if (rowChangeDTO.getRowId() != null) {
            return rowChangeDTO.getRowId();
        }
        return extractRowId(rowChangeDTO.getCurrentData());
    }

    private static Long extractRowId(Map<String, Object> row) {
        Assert.notNull(row, "Snapshot row data cannot be null.");
        Object id = row.get(ModelConstant.ID);
        Assert.notNull(id, "Snapshot row id cannot be null. {0}", row);
        if (id instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(id));
    }

    private static Map<String, Object> compareFieldValues(Map<String, Object> snapshotRow,
                                                           Map<String, Object> runtimeRow,
                                                           Set<String> fields) {
        Map<String, Object> diffs = new HashMap<>();
        for (String field : fields) {
            Object snapshotVal = snapshotRow.get(field);
            Object runtimeVal = runtimeRow.get(field);
            if (!Objects.equals(snapshotVal, runtimeVal)) {
                diffs.put(field, runtimeVal);
            }
        }
        return diffs;
    }

    private static Map<String, Object> extractFields(Map<String, Object> row, Set<String> fieldNames) {
        Map<String, Object> result = new HashMap<>();
        for (String field : fieldNames) {
            result.put(field, row.get(field));
        }
        return result;
    }

    private static RowChangeDTO toRowChangeDTO(String modelName, AccessType accessType, Map<String, Object> row) {
        RowChangeDTO dto = new RowChangeDTO(modelName, extractRowId(row));
        dto.setAccessType(accessType);
        dto.setCurrentData(new HashMap<>(row));
        dto.setLastChangedById((Long) row.get(ModelConstant.UPDATED_ID));
        dto.setLastChangedBy((String) row.get(ModelConstant.UPDATED_BY));
        dto.setLastChangedTime(DateUtils.dateTimeToString(row.get(ModelConstant.UPDATED_TIME)));
        return dto;
    }


}
