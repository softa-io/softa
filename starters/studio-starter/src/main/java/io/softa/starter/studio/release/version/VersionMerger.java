package io.softa.starter.studio.release.version;

import java.util.*;

import io.softa.framework.orm.enums.AccessType;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;

/**
 * Merges multiple {@link ModelChangesDTO} lists (from different Versions in a Release)
 * into a single consolidated list, with proper rowId-level state-machine merging.
 * <p>
 * When multiple Versions in a Release touch the same model and the same rowId, the changes
 * must be folded together according to the following rules (V1 is the earlier version, V2 is the later):
 * <pre>
 *   V1 action  |  V2 action  |  Net result
 *   -----------|-------------|-------------------------------------------
 *   CREATE     |  UPDATE     |  CREATE  (with V2 currentData)
 *   CREATE     |  DELETE     |  (cancelled — no net change)
 *   UPDATE     |  UPDATE     |  UPDATE  (merge dataBeforeChange / dataAfterChange)
 *   UPDATE     |  DELETE     |  DELETE  (use V1 dataBeforeChange)
 *   DELETE     |  CREATE     |  UPDATE  (re-creation treated as update)
 * </pre>
 * Versions are applied in sequence order (ascending), so later Versions override earlier ones.
 */
public final class VersionMerger {

    private VersionMerger() {}

    /**
     * Merge multiple ordered {@code List<ModelChangesDTO>} (one per Version) into a single list.
     * Each inner list is the versionedContent of one {@link io.softa.starter.studio.release.entity.DesignAppVersion}.
     *
     * @param orderedVersionChanges version contents in sequence order (ascending)
     * @return merged list, one {@link ModelChangesDTO} per model that has net changes
     */
    public static List<ModelChangesDTO> merge(List<List<ModelChangesDTO>> orderedVersionChanges) {
        // modelName → (rowId → RowChangeDTO)
        // Using LinkedHashMap to preserve model insertion order
        Map<String, Map<Long, RowChangeDTO>> merged = new LinkedHashMap<>();

        for (List<ModelChangesDTO> versionChanges : orderedVersionChanges) {
            if (versionChanges == null) {
                continue;
            }
            for (ModelChangesDTO modelChanges : versionChanges) {
                String modelName = modelChanges.getModelName();
                Map<Long, RowChangeDTO> rowMap = merged.computeIfAbsent(modelName, k -> new LinkedHashMap<>());
                // Apply each row change from this version
                for (RowChangeDTO row : modelChanges.getCreatedRows()) {
                    applyRow(rowMap, row);
                }
                for (RowChangeDTO row : modelChanges.getUpdatedRows()) {
                    applyRow(rowMap, row);
                }
                for (RowChangeDTO row : modelChanges.getDeletedRows()) {
                    applyRow(rowMap, row);
                }
            }
        }

        // Build the result, filtering out cancelled rows
        List<ModelChangesDTO> result = new ArrayList<>();
        for (Map.Entry<String, Map<Long, RowChangeDTO>> entry : merged.entrySet()) {
            ModelChangesDTO dto = new ModelChangesDTO(entry.getKey());
            for (RowChangeDTO row : entry.getValue().values()) {
                if (row == null) {
                    // Cancelled (CREATE + DELETE)
                    continue;
                }
                switch (row.getAccessType()) {
                    case CREATE -> dto.addCreatedRow(row);
                    case UPDATE -> dto.addUpdatedRow(row);
                    case DELETE -> dto.addDeletedRow(row);
                }
            }
            if (!dto.getCreatedRows().isEmpty() || !dto.getUpdatedRows().isEmpty()
                    || !dto.getDeletedRows().isEmpty()) {
                result.add(dto);
            }
        }
        return result;
    }

    /**
     * Apply a later RowChangeDTO onto the existing state for the same rowId.
     * <p>
     * State machine:
     * <ul>
     *   <li>No existing row → insert directly</li>
     *   <li>Existing CREATE + new UPDATE → stays CREATE (update currentData)</li>
     *   <li>Existing CREATE + new DELETE → cancel (remove from map via null marker)</li>
     *   <li>Existing UPDATE + new UPDATE → stays UPDATE (merge before/after)</li>
     *   <li>Existing UPDATE + new DELETE → becomes DELETE (preserve original dataBeforeChange)</li>
     *   <li>Existing DELETE + new CREATE → becomes UPDATE (re-creation)</li>
     * </ul>
     */
    private static void applyRow(Map<Long, RowChangeDTO> rowMap, RowChangeDTO laterRow) {
        Long rowId = laterRow.getRowId();
        RowChangeDTO existing = rowMap.get(rowId);

        if (existing == null && !rowMap.containsKey(rowId)) {
            // First time seeing this rowId — insert as-is
            rowMap.put(rowId, copyRow(laterRow));
            return;
        }

        if (existing == null) {
            // Was previously cancelled (null marker from CREATE+DELETE).
            // Now re-appearing means re-creation from scratch.
            rowMap.put(rowId, copyRow(laterRow));
            return;
        }

        AccessType existingType = existing.getAccessType();
        AccessType laterType = laterRow.getAccessType();

        if (existingType == AccessType.CREATE && laterType == AccessType.UPDATE) {
            // CREATE + UPDATE = CREATE with updated data
            existing.setCurrentData(laterRow.getCurrentData());
            // dataBeforeChange stays empty (it's a new row, no "before" state)
            // dataAfterChange: merge in the later changes
            existing.mergeDataAfterChange(laterRow.getDataAfterChange());
            existing.setLastChangedById(laterRow.getLastChangedById());
            existing.setLastChangedBy(laterRow.getLastChangedBy());
            existing.setLastChangedTime(laterRow.getLastChangedTime());
        } else if (existingType == AccessType.CREATE && laterType == AccessType.DELETE) {
            // CREATE + DELETE = net zero — mark as cancelled
            rowMap.put(rowId, null);
        } else if (existingType == AccessType.UPDATE && laterType == AccessType.UPDATE) {
            // UPDATE + UPDATE = UPDATE with merged changes
            existing.setCurrentData(laterRow.getCurrentData());
            // dataBeforeChange: keep the earliest "before" (existing already has it)
            // dataAfterChange: later version wins for overlapping fields
            existing.mergeDataAfterChange(laterRow.getDataAfterChange());
            existing.setLastChangedById(laterRow.getLastChangedById());
            existing.setLastChangedBy(laterRow.getLastChangedBy());
            existing.setLastChangedTime(laterRow.getLastChangedTime());
        } else if (existingType == AccessType.UPDATE && laterType == AccessType.DELETE) {
            // UPDATE + DELETE = DELETE, but preserve original dataBeforeChange
            existing.setAccessType(AccessType.DELETE);
            existing.setCurrentData(laterRow.getCurrentData());
            // dataBeforeChange stays from the original UPDATE (earliest known state)
            existing.setLastChangedById(laterRow.getLastChangedById());
            existing.setLastChangedBy(laterRow.getLastChangedBy());
            existing.setLastChangedTime(laterRow.getLastChangedTime());
        } else if (existingType == AccessType.DELETE && laterType == AccessType.CREATE) {
            // DELETE + CREATE = UPDATE (row was deleted then re-created, net effect is update)
            existing.setAccessType(AccessType.UPDATE);
            existing.setCurrentData(laterRow.getCurrentData());
            // dataBeforeChange: the DELETE's currentData was the state before deletion
            // dataAfterChange: the CREATE's currentData is the new state
            existing.setDataAfterChange(new HashMap<>(laterRow.getCurrentData()));
            existing.setLastChangedById(laterRow.getLastChangedById());
            existing.setLastChangedBy(laterRow.getLastChangedBy());
            existing.setLastChangedTime(laterRow.getLastChangedTime());
        } else {
            // Unexpected combination (e.g., CREATE+CREATE, DELETE+DELETE, DELETE+UPDATE)
            // Treat as override — later version wins entirely
            rowMap.put(rowId, copyRow(laterRow));
        }
    }

    /**
     * Defensive copy of a RowChangeDTO to avoid mutating the source Version's data.
     */
    private static RowChangeDTO copyRow(RowChangeDTO source) {
        RowChangeDTO copy = new RowChangeDTO(source.getModel(), source.getRowId());
        copy.setAccessType(source.getAccessType());
        copy.setCurrentData(source.getCurrentData() != null ? new HashMap<>(source.getCurrentData()) : new HashMap<>());
        copy.setDataBeforeChange(source.getDataBeforeChange() != null ? new HashMap<>(source.getDataBeforeChange()) : new HashMap<>());
        copy.setDataAfterChange(source.getDataAfterChange() != null ? new HashMap<>(source.getDataAfterChange()) : new HashMap<>());
        copy.setLastChangedById(source.getLastChangedById());
        copy.setLastChangedBy(source.getLastChangedBy());
        copy.setLastChangedTime(source.getLastChangedTime());
        return copy;
    }
}

