package io.softa.starter.studio.release.dto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.softa.starter.metadata.dto.MetaTable;

/**
 * The 1:1 correspondence between a studio {@code design_*} meta-table and its typed {@link MetaTable},
 * plus the flat→grouped regrouper.
 * <p>
 * Since the wire reshape, the diff is a flat {@link RowChangeDTO} list (each row self-describing
 * via {@code table} + {@code op}). Consumers that need a per-table / per-op view (the DDL renderer,
 * env↔env merge, import, drift report) regroup on demand via {@link #group}; the design-side writers
 * resolve back to the {@code Design*} model name for {@code ModelService} calls via
 * {@link #designModelName}.
 */
public final class DesignMetaTables {

    // Derived from the DesignAggregate descriptor — the single source of the design↔MetaTable pairing.
    private static final Map<String, MetaTable> BY_DESIGN = Arrays.stream(DesignAggregate.values())
            .collect(Collectors.toUnmodifiableMap(DesignAggregate::designName, DesignAggregate::table));

    private static final Map<MetaTable, String> DESIGN_BY_TABLE = new EnumMap<>(MetaTable.class);
    static {
        BY_DESIGN.forEach((name, table) -> DESIGN_BY_TABLE.put(table, name));
    }

    private DesignMetaTables() {}

    /** The {@link MetaTable} for a {@code Design*} meta-model simple name. */
    public static MetaTable of(String designModelSimpleName) {
        return BY_DESIGN.get(designModelSimpleName);
    }

    /** The {@code Design*} meta-model simple name for a {@link MetaTable}. */
    public static String designModelName(MetaTable table) {
        return DESIGN_BY_TABLE.get(table);
    }

    /**
     * Regroup a flat row-change list into one {@link ModelChangesDTO} per {@link MetaTable} (in
     * parent→child ordinal order), op-bucketed (CREATE→created, UPDATE→updated, DELETE→deleted). A
     * derived view for consumers that render / write per table; the flat list stays the source of truth.
     */
    public static List<ModelChangesDTO> group(List<RowChangeDTO> rows) {
        Map<MetaTable, ModelChangesDTO> byTable = new EnumMap<>(MetaTable.class);
        for (RowChangeDTO row : rows) {
            ModelChangesDTO dto = byTable.computeIfAbsent(row.getTable(),
                    t -> new ModelChangesDTO(designModelName(t)));
            switch (row.getOp()) {
                case CREATE -> dto.addCreatedRow(row);
                case UPDATE -> dto.addUpdatedRow(row);
                case DELETE -> dto.addDeletedRow(row);
            }
        }
        return new ArrayList<>(byTable.values());
    }
}
