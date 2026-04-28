package io.softa.starter.studio.release.version.impl;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.es.service.ChangeLogService;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.version.VersionControl;

import static io.softa.framework.orm.enums.AccessType.UPDATE;

/**
 * Version control implementation
 */
@Component
public class VersionControlImpl implements VersionControl {

    @Autowired
    private ChangeLogService changeLogService;

    @Autowired
    private ModelService<Long> modelService;

    /**
     * Collect model-level changes for all version-controlled models from the specified WorkItems.
     *
     * @param workItemIds list of WorkItem IDs whose changes to aggregate
     * @return list of model-level change summaries, excluding empty models
     */
    @Override
    public List<ModelChangesDTO> collectModelChanges(List<Long> workItemIds) {
        if (workItemIds == null || workItemIds.isEmpty()) {
            return List.of();
        }
        List<String> versionedModels = new ArrayList<>(MetadataConstant.VERSION_CONTROL_MODELS.keySet());
        List<ChangeLog> allChangeLogs = changeLogService.searchByCorrelationIds(versionedModels, toCorrelationIds(workItemIds));
        if (allChangeLogs.isEmpty()) {
            return List.of();
        }
        Map<String, List<ChangeLog>> logsByModel = allChangeLogs.stream()
                .collect(Collectors.groupingBy(ChangeLog::getModel, LinkedHashMap::new, Collectors.toList()));
        List<ModelChangesDTO> result = new ArrayList<>();
        for (String versionedModel : versionedModels) {
            ModelChangesDTO changes = buildModelChanges(versionedModel, logsByModel.get(versionedModel));
            if (changes != null) {
                result.add(changes);
            }
        }
        return result;
    }

    private List<String> toCorrelationIds(List<Long> workItemIds) {
        return workItemIds.stream()
                .map(String::valueOf)
                .collect(Collectors.toList());
    }

    private ModelChangesDTO buildModelChanges(String versionedModel, List<ChangeLog> allChangeLogs) {
        if (allChangeLogs == null || allChangeLogs.isEmpty()) {
            return null;
        }

        // Group changelogs by rowId, preserving insertion order (changedTime ASC from ES)
        Map<Long, List<ChangeLog>> logsByRow = allChangeLogs.stream()
                .collect(Collectors.groupingBy(changeLog -> Long.parseLong(changeLog.getRowId()), LinkedHashMap::new, Collectors.toList()));

        // Only rows that existed before this WorkItem set still need a DB lookup.
        // CREATE rows are reconstructed strictly from changelog history.
        Set<Long> rowIdsNeedingDbLookup = new HashSet<>();
        for (Map.Entry<Long, List<ChangeLog>> entry : logsByRow.entrySet()) {
            List<ChangeLog> rowLogs = entry.getValue();
            AccessType firstType = rowLogs.getFirst().getAccessType();
            AccessType lastType = rowLogs.getLast().getAccessType();
            if (firstType != AccessType.CREATE && lastType != AccessType.DELETE) {
                rowIdsNeedingDbLookup.add(entry.getKey());
            }
        }

        // Fetch the current DB state for surviving rows that pre-existed this WorkItem set.
        Map<Long, Map<String, Object>> currentDataMap = new HashMap<>();
        if (!rowIdsNeedingDbLookup.isEmpty()) {
            List<Map<String, Object>> dbRows = getCurrentDataByIds(versionedModel, rowIdsNeedingDbLookup);
            dbRows.forEach(row -> currentDataMap.put((Long) row.get(ModelConstant.ID), row));
        }

        ModelChangesDTO modelChangesDTO = new ModelChangesDTO(versionedModel);
        for (Map.Entry<Long, List<ChangeLog>> entry : logsByRow.entrySet()) {
            Long rowId = entry.getKey();
            List<ChangeLog> logs = entry.getValue();
            AccessType firstType = logs.getFirst().getAccessType();
            AccessType lastType = logs.getLast().getAccessType();

            if (firstType == AccessType.CREATE && lastType == AccessType.DELETE) {
                // Row was created and deleted within the same WorkItem set — net effect: no change
                continue;
            } else if (lastType == AccessType.DELETE) {
                // Row existed before this WorkItem set and was deleted.
                // Use the DELETE log's dataBeforeChange as the final row state.
                ChangeLog deleteLog = logs.getLast();
                RowChangeDTO rowChangeDTO = new RowChangeDTO(versionedModel, rowId);
                rowChangeDTO.setAccessType(AccessType.DELETE);
                rowChangeDTO.setCurrentData(deleteLog.getDataBeforeChange() == null
                        ? new HashMap<>()
                        : new HashMap<>(deleteLog.getDataBeforeChange()));
                rowChangeDTO.setLastChangedById(deleteLog.getChangedById());
                rowChangeDTO.setLastChangedBy(deleteLog.getChangedBy());
                rowChangeDTO.setLastChangedTime(deleteLog.getChangedTime());
                modelChangesDTO.addDeletedRow(rowChangeDTO);
            } else if (firstType == AccessType.CREATE) {
                // Row was created within this WorkItem set.
                modelChangesDTO.addCreatedRow(mergeCreatedToRowChangeDTO(logs));
            } else {
                // Row existed before and was updated within this WorkItem set.
                Map<String, Object> currentData = currentDataMap.get(rowId);
                if (currentData == null) {
                    // Row no longer exists in DB — skip
                    continue;
                }
                RowChangeDTO rowChangeDTO = mergeUpdatedToRowChangeDTO(logs, currentData);
                modelChangesDTO.addUpdatedRow(rowChangeDTO);
            }
        }

        if (modelChangesDTO.getCreatedRows().isEmpty() && modelChangesDTO.getUpdatedRows().isEmpty()
                && modelChangesDTO.getDeletedRows().isEmpty()) {
            return null;
        }
        return modelChangesDTO;
    }

    /**
     * Get the current DB state for specific row IDs within the given app.
     * Used by the WorkItem-centric path to look up current data for surviving UPDATE rows.
     *
     * @param versionedModel version-controlled design model name
     * @param rowIds         specific row IDs to fetch
     * @return list of current row data maps
     */
    private List<Map<String, Object>> getCurrentDataByIds(String versionedModel,
            Collection<Long> rowIds) {
        Filters filters = new Filters().in(ModelConstant.ID, rowIds);
        if (ModelManager.isSoftDeleted(versionedModel)) {
            String softDeleteField = ModelManager.getSoftDeleteField(versionedModel);
            filters.in(softDeleteField, Arrays.asList(false, true, null));
        }
        Set<String> fields = ModelManager.getModelFieldsWithoutXToMany(versionedModel);
        FlexQuery flexQuery = new FlexQuery(fields, filters);
        return modelService.searchList(versionedModel, flexQuery);
    }

    /**
     * Rebuild a CREATE row strictly from historical changelog data.
     *
     * @param changeLogs List of change records with the same id, sorted in ascending order
     * @return RowChangeDTO object
     */
    private static RowChangeDTO mergeCreatedToRowChangeDTO(List<ChangeLog> changeLogs) {
        ChangeLog createLog = changeLogs.getFirst();
        ChangeLog lastLog = changeLogs.getLast();
        Map<String, Object> currentData = createLog.getDataAfterChange() != null
                ? new HashMap<>(createLog.getDataAfterChange())
                : new HashMap<>();
        for (int i = 1; i < changeLogs.size(); i++) {
            ChangeLog changeLog = changeLogs.get(i);
            if (changeLog.getDataAfterChange() != null) {
                currentData.putAll(changeLog.getDataAfterChange());
            }
        }
        RowChangeDTO rowChangeDTO = new RowChangeDTO(createLog.getModel(), Long.parseLong(createLog.getRowId()));
        rowChangeDTO.setAccessType(AccessType.CREATE);
        rowChangeDTO.setCurrentData(new HashMap<>(currentData));
        rowChangeDTO.setDataAfterChange(new HashMap<>(currentData));
        rowChangeDTO.setLastChangedById(lastLog.getChangedById());
        rowChangeDTO.setLastChangedBy(lastLog.getChangedBy());
        rowChangeDTO.setLastChangedTime(lastLog.getChangedTime());
        return rowChangeDTO;
    }

    /**
     * Merge List<ChangeLog> with the same id into one RowChangeDTO
     *
     * @param changeLogs List of change records with the same id, sorted in ascending order
     * @param currentData Current data
     * @return RowChangeDTO object
     */
    private static RowChangeDTO mergeUpdatedToRowChangeDTO(List<ChangeLog> changeLogs, Map<String, Object> currentData) {
        ChangeLog lastLog = changeLogs.getLast();
        RowChangeDTO rowChangeDTO = new RowChangeDTO(lastLog.getModel(), Long.parseLong(lastLog.getRowId()));
        rowChangeDTO.setAccessType(UPDATE);
        rowChangeDTO.setCurrentData(new HashMap<>(currentData));
        rowChangeDTO.setLastChangedById(lastLog.getChangedById());
        rowChangeDTO.setLastChangedBy(lastLog.getChangedBy());
        rowChangeDTO.setLastChangedTime(lastLog.getChangedTime());
        // Merge data before the change in DESC order
        for (int i = changeLogs.size() - 1; i >= 0; i--) {
            ChangeLog changeLog = changeLogs.get(i);
            rowChangeDTO.mergeDataBeforeChange(changeLog.getDataBeforeChange());
        }
        // Merge data after the change in ASC order
        for (ChangeLog changeLog : changeLogs) {
            rowChangeDTO.mergeDataAfterChange(changeLog.getDataAfterChange());
        }
        // Drop fields that net-out to no change (e.g. A→B→A) and trim dataBeforeChange
        // to the actually-changed keys, since the producer stores it as a full original
        // row snapshot while dataAfterChange only carries written fields.
        Map<String, Object> before = rowChangeDTO.getDataBeforeChange();
        Map<String, Object> after = rowChangeDTO.getDataAfterChange();
        after.entrySet().removeIf(e -> Objects.equals(e.getValue(), before.get(e.getKey())));
        before.keySet().retainAll(after.keySet());
        return rowChangeDTO;
    }

}
