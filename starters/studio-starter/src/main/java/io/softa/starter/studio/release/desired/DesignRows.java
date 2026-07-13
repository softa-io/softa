package io.softa.starter.studio.release.desired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.starter.studio.release.dto.DesignAggregate;

/**
 * A metadata catalog as camelCase attribute maps, one list per meta-table (model / field / index /
 * option-set / option-item). The shared shape on both sides of a converge: {@link DesignEnvSource}
 * loads the design (desired) side, a
 * {@link io.softa.starter.studio.release.connector.Connector#readSchema connector} reads the runtime
 * (observed) side, and {@link DesignAggregateDiffer#diff} diffs the two.
 */
public record DesignRows(List<Map<String, Object>> models,
                         List<Map<String, Object>> fields,
                         List<Map<String, Object>> indexes,
                         List<Map<String, Object>> optionSets,
                         List<Map<String, Object>> items) {

    /** An empty catalog — the observed side when nothing needs fetching (a pure create). */
    public static DesignRows empty() {
        return new DesignRows(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** The row list of one swept meta-table, addressed by its {@link DesignAggregate} descriptor. */
    public List<Map<String, Object>> rows(DesignAggregate aggregate) {
        return switch (aggregate) {
            case MODEL -> models();
            case OPTION_SET -> optionSets();
            case FIELD -> fields();
            case INDEX -> indexes();
            case OPTION_ITEM -> items();
        };
    }

    /**
     * The sub-catalog restricted to the named aggregates: keep only the model /
     * option-set roots whose business key is in {@code modelNames} / {@code optionSetCodes}, and only
     * the child rows whose <i>parent</i> business key is in the same set. Whole aggregates are kept or
     * dropped together — never split — so feeding this to {@link DesignAggregateDiffer#diff} produces
     * exactly the changes for the selected aggregates and nothing for the omitted ones.
     *
     * <p>Rows are keyed by the same business-key columns the differ and the checksum use
     * ({@link AggregateChecksumDiff#MODEL_NAME} / {@link AggregateChecksumDiff#OPTION_SET_CODE}), so a
     * design row and its runtime counterpart restrict identically.
     */
    public DesignRows select(Set<String> modelNames, Set<String> optionSetCodes) {
        return new DesignRows(
                byKey(models, AggregateChecksumDiff.MODEL_NAME, modelNames),
                byKey(fields, AggregateChecksumDiff.MODEL_NAME, modelNames),
                byKey(indexes, AggregateChecksumDiff.MODEL_NAME, modelNames),
                byKey(optionSets, AggregateChecksumDiff.OPTION_SET_CODE, optionSetCodes),
                byKey(items, AggregateChecksumDiff.OPTION_SET_CODE, optionSetCodes));
    }

    /** Rows whose {@code keyColumn} value is in {@code keep} (own key for roots, parent key for children). */
    private static List<Map<String, Object>> byKey(List<Map<String, Object>> rows,
                                                   String keyColumn, Set<String> keep) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(r -> keep.contains(r.get(keyColumn) == null ? null : String.valueOf(r.get(keyColumn))))
                .toList();
    }
}
