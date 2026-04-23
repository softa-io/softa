package io.softa.starter.studio.release.upgrade;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.metadata.dto.MetadataUpgradePackage;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;

import static io.softa.framework.orm.constant.ModelConstant.ID;

/**
 * Convert design-time {@link ModelChangesDTO} into the runtime-facing
 * {@link MetadataUpgradePackage} wire format, splitting oversized models into
 * sub-packages to respect {@code MAX_BATCH_SIZE}.
 * <p>
 * Actual dispatch lives in {@code DesignDeploymentServiceImpl}, which posts the
 * packages to the target runtime via {@link RemoteApiClient} with the async
 * envelope + callback flow.
 */
@Component
public class DeploymentExecutor {

    /**
     * Convert design-time ModelChangesDTO list to runtime MetadataUpgradePackage list.
     * When a single model has more rows than {@code MAX_BATCH_SIZE}, it is split into
     * multiple packages to stay within the batch size limit.
     *
     * @param modelChangesDTOList List of design-time changed model data
     * @return List of runtime model upgrade packages
     */
    public List<MetadataUpgradePackage> convertToUpgradePackages(List<ModelChangesDTO> modelChangesDTOList) {
        List<MetadataUpgradePackage> upgradeModelPackages = new ArrayList<>();
        if (CollectionUtils.isEmpty(modelChangesDTOList)) {
            return upgradeModelPackages;
        }
        for (ModelChangesDTO modelChangesDTO : modelChangesDTOList) {
            String runtimeModel = MetadataConstant.VERSION_CONTROL_MODELS.get(modelChangesDTO.getModelName());
            // created data
            List<Map<String, Object>> createRows = modelChangesDTO.getCreatedRows().stream()
                    .map(RowChangeDTO::getCurrentData).toList();
            // updated data
            List<Map<String, Object>> updateRows = new ArrayList<>();
            for (RowChangeDTO rowChangeDTO : modelChangesDTO.getUpdatedRows()) {
                Map<String, Object> rowMap = new HashMap<>();
                rowMap.put(ID, rowChangeDTO.getRowId());
                rowMap.putAll(rowChangeDTO.getDataAfterChange());
                updateRows.add(rowMap);
            }
            // deleted ids
            List<Long> deletedIds = modelChangesDTO.getDeletedRows().stream()
                    .map(RowChangeDTO::getRowId)
                    .toList();

            int totalRows = createRows.size() + updateRows.size() + deletedIds.size();
            if (totalRows <= BaseConstant.MAX_BATCH_SIZE) {
                // Single package fits within batch limit
                MetadataUpgradePackage pkg = new MetadataUpgradePackage();
                pkg.setModelName(runtimeModel);
                pkg.setCreateRows(createRows);
                pkg.setUpdateRows(updateRows);
                pkg.setDeleteIds(deletedIds);
                upgradeModelPackages.add(pkg);
            } else {
                // Split into multiple packages to respect MAX_BATCH_SIZE
                splitIntoPackages(upgradeModelPackages, runtimeModel, createRows, updateRows, deletedIds);
            }
        }
        return upgradeModelPackages;
    }

    /**
     * Split large model data into multiple MetadataUpgradePackage instances,
     * each containing at most MAX_BATCH_SIZE rows.
     * Ordering: deletes first, then updates, then creates — to avoid conflicts within a batch.
     */
    private void splitIntoPackages(List<MetadataUpgradePackage> packages, String runtimeModel,
                                   List<Map<String, Object>> createRows,
                                   List<Map<String, Object>> updateRows,
                                   List<Long> deletedIds) {
        int batchSize = BaseConstant.MAX_BATCH_SIZE;
        // Split deletes
        for (int i = 0; i < deletedIds.size(); i += batchSize) {
            MetadataUpgradePackage pkg = new MetadataUpgradePackage();
            pkg.setModelName(runtimeModel);
            pkg.setCreateRows(Collections.emptyList());
            pkg.setUpdateRows(Collections.emptyList());
            pkg.setDeleteIds(deletedIds.subList(i, Math.min(i + batchSize, deletedIds.size())));
            packages.add(pkg);
        }
        // Split updates
        for (int i = 0; i < updateRows.size(); i += batchSize) {
            MetadataUpgradePackage pkg = new MetadataUpgradePackage();
            pkg.setModelName(runtimeModel);
            pkg.setCreateRows(Collections.emptyList());
            pkg.setUpdateRows(updateRows.subList(i, Math.min(i + batchSize, updateRows.size())));
            pkg.setDeleteIds(Collections.emptyList());
            packages.add(pkg);
        }
        // Split creates
        for (int i = 0; i < createRows.size(); i += batchSize) {
            MetadataUpgradePackage pkg = new MetadataUpgradePackage();
            pkg.setModelName(runtimeModel);
            pkg.setCreateRows(createRows.subList(i, Math.min(i + batchSize, createRows.size())));
            pkg.setUpdateRows(Collections.emptyList());
            pkg.setDeleteIds(Collections.emptyList());
            packages.add(pkg);
        }
    }

}
