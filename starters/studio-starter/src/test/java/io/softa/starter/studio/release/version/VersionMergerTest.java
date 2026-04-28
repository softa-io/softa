package io.softa.starter.studio.release.version;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.AccessType;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionMergerTest {

    private static final String MODEL = "DesignField";

    @Test
    void updatePlusUpdateMergesDisjointFieldsWithCorrectBeforeValues() {
        RowChangeDTO v1 = updateRow(1L,
                Map.of("name", "A"),
                Map.of("name", "B"),
                Map.of("name", "B", "email", "x"));
        RowChangeDTO v2 = updateRow(1L,
                Map.of("email", "x"),
                Map.of("email", "y"),
                Map.of("name", "B", "email", "y"));

        ModelChangesDTO merged = mergeUpdates(v1, v2);

        assertEquals(1, merged.getUpdatedRows().size());
        RowChangeDTO row = merged.getUpdatedRows().getFirst();
        assertEquals(Map.of("name", "A", "email", "x"), row.getDataBeforeChange());
        assertEquals(Map.of("name", "B", "email", "y"), row.getDataAfterChange());
    }

    @Test
    void updatePlusUpdateDropsFieldsThatNetOutToNoChange() {
        // V1: name A → B, status active → inactive
        RowChangeDTO v1 = updateRow(1L,
                Map.of("name", "A", "status", "active"),
                Map.of("name", "B", "status", "inactive"),
                Map.of("name", "B", "status", "inactive"));
        // V2: name B → A (revert), status inactive → archived
        RowChangeDTO v2 = updateRow(1L,
                Map.of("name", "B", "status", "inactive"),
                Map.of("name", "A", "status", "archived"),
                Map.of("name", "A", "status", "archived"));

        ModelChangesDTO merged = mergeUpdates(v1, v2);

        RowChangeDTO row = merged.getUpdatedRows().getFirst();
        // name was reverted A→B→A — must be dropped from both maps
        assertTrue(!row.getDataAfterChange().containsKey("name"),
                "reverted field must not appear in dataAfterChange");
        assertTrue(!row.getDataBeforeChange().containsKey("name"),
                "reverted field must not appear in dataBeforeChange");
        // status really changed active → archived
        assertEquals("active", row.getDataBeforeChange().get("status"));
        assertEquals("archived", row.getDataAfterChange().get("status"));
    }

    @Test
    void deletePlusCreateBecomesUpdateWithPreDeletionSnapshotAsBefore() {
        // V1 deletes the row; mergeUpdated/buildModelChanges sets currentData to the pre-deletion snapshot
        RowChangeDTO v1Delete = new RowChangeDTO(MODEL, 1L);
        v1Delete.setAccessType(AccessType.DELETE);
        v1Delete.setCurrentData(new HashMap<>(Map.of("name", "A", "status", "active")));

        // V2 creates a row with the same id but different content
        RowChangeDTO v2Create = new RowChangeDTO(MODEL, 1L);
        v2Create.setAccessType(AccessType.CREATE);
        v2Create.setCurrentData(new HashMap<>(Map.of("name", "B", "status", "active")));
        v2Create.setDataAfterChange(new HashMap<>(Map.of("name", "B", "status", "active")));

        ModelChangesDTO merged = mergeRows(v1Delete, v2Create);

        assertEquals(0, merged.getDeletedRows().size(), "DELETE+CREATE should not surface as DELETE");
        assertEquals(0, merged.getCreatedRows().size(), "DELETE+CREATE should not surface as CREATE");
        assertEquals(1, merged.getUpdatedRows().size(), "DELETE+CREATE should net to UPDATE");

        RowChangeDTO row = merged.getUpdatedRows().getFirst();
        // Only `name` actually differs; `status` was the same in both states and must be dropped
        assertEquals(Map.of("name", "A"), row.getDataBeforeChange());
        assertEquals(Map.of("name", "B"), row.getDataAfterChange());
        // currentData reflects the post-recreation state
        assertEquals("B", row.getCurrentData().get("name"));
    }

    @Test
    void deletePlusCreateWithIdenticalContentIsFilteredOut() {
        // V1 deletes a row that had {name: A}; V2 creates one back with the exact same content
        RowChangeDTO v1Delete = new RowChangeDTO(MODEL, 1L);
        v1Delete.setAccessType(AccessType.DELETE);
        v1Delete.setCurrentData(new HashMap<>(Map.of("name", "A")));

        RowChangeDTO v2Create = new RowChangeDTO(MODEL, 1L);
        v2Create.setAccessType(AccessType.CREATE);
        v2Create.setCurrentData(new HashMap<>(Map.of("name", "A")));
        v2Create.setDataAfterChange(new HashMap<>(Map.of("name", "A")));

        ModelChangesDTO d1 = new ModelChangesDTO(MODEL);
        d1.addDeletedRow(v1Delete);
        ModelChangesDTO d2 = new ModelChangesDTO(MODEL);
        d2.addCreatedRow(v2Create);

        // Net effect is no change — the empty-diff UPDATE row is filtered, and the
        // ModelChangesDTO has no rows left, so it drops out of the merged result entirely.
        List<ModelChangesDTO> merged = VersionMerger.merge(List.of(List.of(d1), List.of(d2)));
        assertTrue(merged.isEmpty(), "rows that net out to no change must not appear in the merged result");
    }

    private static RowChangeDTO updateRow(Long rowId,
                                          Map<String, Object> before,
                                          Map<String, Object> after,
                                          Map<String, Object> currentData) {
        RowChangeDTO row = new RowChangeDTO(MODEL, rowId);
        row.setAccessType(AccessType.UPDATE);
        row.setDataBeforeChange(new HashMap<>(before));
        row.setDataAfterChange(new HashMap<>(after));
        row.setCurrentData(new HashMap<>(currentData));
        return row;
    }

    private static ModelChangesDTO mergeUpdates(RowChangeDTO... rows) {
        List<List<ModelChangesDTO>> versions = new java.util.ArrayList<>();
        for (RowChangeDTO row : rows) {
            ModelChangesDTO dto = new ModelChangesDTO(MODEL);
            dto.addUpdatedRow(row);
            versions.add(List.of(dto));
        }
        List<ModelChangesDTO> merged = VersionMerger.merge(versions);
        return merged.getFirst();
    }

    private static ModelChangesDTO mergeRows(RowChangeDTO v1, RowChangeDTO v2) {
        ModelChangesDTO d1 = new ModelChangesDTO(MODEL);
        addToBucket(d1, v1);
        ModelChangesDTO d2 = new ModelChangesDTO(MODEL);
        addToBucket(d2, v2);
        return VersionMerger.merge(List.of(List.of(d1), List.of(d2))).getFirst();
    }

    private static void addToBucket(ModelChangesDTO dto, RowChangeDTO row) {
        switch (row.getAccessType()) {
            case CREATE -> dto.addCreatedRow(row);
            case UPDATE -> dto.addUpdatedRow(row);
            case DELETE -> dto.addDeletedRow(row);
        }
    }
}
