package io.softa.starter.studio.release.desired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.studio.release.dto.DesignAggregate;
import io.softa.starter.studio.release.dto.DesignMetaTables;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.dto.RowChangeOp;

/**
 * The single metadata differ: a <b>business-key-keyed</b> diff of DESIRED vs OBSERVED row-sets,
 * with a <b>{@code renamedFrom} bridge</b> as a subordinate second pass. Used by BOTH converge directions:
 * <ul>
 *   <li><b>publish</b> (env design ↔ its runtime catalog) — rows pair on the current business key
 *       (model={@code modelName}, field={@code modelName}+{@code fieldName}, …);</li>
 *   <li><b>env↔env merge</b> (design ↔ design) — same business-key pairing (each env enforces
 *       {@code UNIQUE(env_id, businessKey)}).</li>
 * </ul>
 *
 * <p><b>Two-pass match</b>: <b>pass A</b> pairs every DESIRED row to the OBSERVED row with the
 * <i>same current business key</i> and consumes all exact matches first (so an exact match always wins over
 * a rename bridge — the set-wide new-absent guard); <b>pass B</b> then bridges a still-unpaired DESIRED row
 * whose {@code renamedFrom} names a still-unpaired OBSERVED business key → an UPDATE carrying the old key in
 * {@code previousValuesForChangedFields} (NOT a drop+add). The bridge is <b>field / optionItem only</b>
 * (single-step, in place); <b>model / optionSet / index</b> renames carry children and are left as
 * drop+add ({@code renameKeyCol} is null for them) — gated downstream by {@code autoExecuteDDL} or a manual
 * migration. {@link io.softa.starter.studio.release.ddl.MetadataChangeDdlRenderer} renders the rename DDL
 * (CHANGE COLUMN) from a bridged UPDATE. {@code renamedFrom} itself is never a compared attr (it is in
 * {@code SysCatalog.EXCLUDED}), and the per-env surrogate FKs ({@code modelId}/{@code optionSetId}) are
 * not in the checksum allow-lists, so neither registers as a spurious change.
 *
 * <p>Output is a flat {@code List<RowChangeDTO>} (each row self-describing via {@code table} + {@code op})
 * that {@link DesiredStateDeployService} (rows) and {@code MetadataChangeDdlRenderer} (DDL) consume directly —
 * regrouped per table on demand via {@link io.softa.starter.studio.release.dto.DesignMetaTables#group}.
 * "Changed" is decided on the same allow-lists the checksum hashes; the per-table wiring (keys, rename
 * bridge, compared attrs, parent link) is the {@link DesignAggregate} descriptor. Both sides are
 * normalized through one JSON round-trip first (enum⇄code, Integer⇄Long). Pure / stateless.
 */
@Component
public class DesignAggregateDiffer {

    private static final TypeReference<Map<String, Object>> ROW_TYPE = new TypeReference<>() {
    };

    /** The single immediately-prior business-key name carried from the design row. */
    private static final String RENAMED_FROM = LambdaUtils.getAttributeName(SysField::getRenamedFrom);

    /**
     * Diff DESIRED against OBSERVED (business key first, {@code renamedFrom} bridge second).
     * DESIRED is the env's design; OBSERVED is its runtime catalog (publish) or another env's design (merge).
     * <p>
     * Per-table wiring — business key, rename-bridge column (field / optionItem only; a parent rename is
     * never bridged), compared attrs and the parent link — comes from the {@link DesignAggregate}
     * descriptor. Children are diffed only when their parent business-key is present on their OWN side (an
     * orphan — a child whose parent row is absent, e.g. left behind by a non-cascading model delete — is
     * excluded). This (a) keeps the differ's row-set identical to the checksum gate's aggregate view
     * (AggregateChecksumIndex only hashes children of an existing parent), so the R5 gate stays sound
     * (inSync ⇒ this diff is empty), and (b) never publishes a child for a non-existent model — which
     * would create a runtime orphan. modelName / optionSetCode are NOT NULL on the design rows.
     */
    public List<RowChangeDTO> diff(DesignRows desired,
                                   DesignRows observed) {
        List<RowChangeDTO> out = new ArrayList<>();
        for (DesignAggregate aggregate : DesignAggregate.values()) {
            List<Map<String, Object>> desiredRows = desired.rows(aggregate);
            List<Map<String, Object>> observedRows = observed.rows(aggregate);
            DesignAggregate parent = aggregate.parent();
            if (parent != null) {
                desiredRows = ownedBy(desiredRows, aggregate.parentCodeAttr(),
                        parentKeys(desired.rows(parent), bizKeyOf(parent)));
                observedRows = ownedBy(observedRows, aggregate.parentCodeAttr(),
                        parentKeys(observed.rows(parent), bizKeyOf(parent)));
            }
            diffTable(out, aggregate.designName(), desiredRows, observedRows,
                    aggregate.checksumAttrs(), bizKeyOf(aggregate), aggregate.renameBridgeAttr());
        }
        return out;
    }

    /** One aggregate's composite business-key function ({@code "."}-joined, single-attr keys unjoined). */
    private static Function<Map<String, Object>, String> bizKeyOf(DesignAggregate aggregate) {
        List<String> attrs = aggregate.bizKeyAttrs();
        if (attrs.size() == 1) {
            String only = attrs.getFirst();
            return row -> str(row.get(only));
        }
        return row -> attrs.stream().map(attr -> str(row.get(attr))).collect(Collectors.joining("."));
    }

    /** The set of parent business keys present in a root row-list (e.g. every {@code modelName}). */
    private static Set<String> parentKeys(List<Map<String, Object>> rows, Function<Map<String, Object>, String> key) {
        Set<String> keys = new HashSet<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                keys.add(key.apply(r));
            }
        }
        return keys;
    }

    /** Keep only child rows whose parent business-key ({@code parentAttr}) is in {@code parentKeys}. */
    private static List<Map<String, Object>> ownedBy(List<Map<String, Object>> rows, String parentAttr,
                                                     Set<String> parentKeys) {
        if (rows == null) {
            return List.of();
        }
        List<Map<String, Object>> kept = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            if (parentKeys.contains(str(r.get(parentAttr)))) {
                kept.add(r);
            }
        }
        return kept;
    }

    private static void diffTable(List<RowChangeDTO> out, String model,
                                  List<Map<String, Object>> desiredRows,
                                  List<Map<String, Object>> observedRows,
                                  List<String> allowList, Function<Map<String, Object>, String> bizKey,
                                  String renameKeyCol) {
        List<Map<String, Object>> desired = normalizeAll(desiredRows);
        List<Map<String, Object>> observed = normalizeAll(observedRows);
        MetaTable table = DesignMetaTables.of(model);   // typed table carried on each row-change

        // OBSERVED is indexed by business key (the business key is the sole identity — no
        // logicalId). obsByOldKey additionally indexes any observed row that itself carries a renamedFrom
        // (the import direction: the runtime/source renamed it) by its prior key.
        Map<String, Map<String, Object>> obsByBizKey = new LinkedHashMap<>();
        Map<String, Map<String, Object>> obsByOldKey = new LinkedHashMap<>();
        for (Map<String, Object> o : observed) {
            obsByBizKey.put(bizKey.apply(o), o);
            String observedOldKey = oldKey(o, bizKey, renameKeyCol);
            if (observedOldKey != null) {
                obsByOldKey.put(observedOldKey, o);
            }
        }

        Set<String> consumed = new HashSet<>();   // business keys of OBSERVED rows already paired

        // Pass A — resolve EVERY exact current-key match first and consume it. Set-wide new-absent guard:
        // a renamedFrom bridge (pass B) must never claim an observed row that is some OTHER desired row's
        // exact current key, else the diff is order-dependent and can render a rename (CHANGE COLUMN) onto
        // the wrong column. An exact match is also a non-rename, so "rename X→Y + add new X" stays rename +
        // create.
        List<Map<String, Object>> bridgeCandidates = new ArrayList<>();
        for (Map<String, Object> d : desired) {
            Map<String, Object> exact = obsByBizKey.get(bizKey.apply(d));
            if (exact != null && !consumed.contains(bizKey.apply(exact))) {
                pairOrSkip(out, table, d, exact, allowList, bizKey, consumed);
            } else {
                bridgeCandidates.add(d);
            }
        }

        // Pass B — renamedFrom bridges, only against observed rows no exact match claimed (deploy: d renamed
        // FROM oldKey(d); import: an observed row renamed from d's current key). Pairs a renamed row in place
        // instead of drop+add.
        for (Map<String, Object> d : bridgeCandidates) {
            Map<String, Object> prev = bridgeObserved(d, bizKey, renameKeyCol, obsByBizKey, obsByOldKey, consumed);
            if (prev == null) {
                out.add(created(table, d));
            } else {
                pairOrSkip(out, table, d, prev, allowList, bizKey, consumed);
            }
        }

        for (Map<String, Object> o : observed) {
            if (!consumed.contains(bizKey.apply(o))) {
                out.add(deleted(table, o));
            }
        }
    }

    /**
     * The OBSERVED row a pass-2b candidate renamed to/from, or null → CREATE. Bridges ONLY —
     * exact current-key matches are resolved and consumed in pass 2a, so a bridge can never claim a row
     * that is another desired row's exact key (set-wide new-absent guard). Deploy: d renamed FROM oldKey(d).
     * Import: an observed row whose own renamedFrom points to d's current key.
     */
    private static Map<String, Object> bridgeObserved(Map<String, Object> d,
            Function<Map<String, Object>, String> bizKey, String renameKeyCol,
            Map<String, Map<String, Object>> obsByBizKey, Map<String, Map<String, Object>> obsByOldKey,
            Set<String> consumed) {
        String desiredOldKey = oldKey(d, bizKey, renameKeyCol);
        if (desiredOldKey != null) {
            Map<String, Object> byDesiredRename = obsByBizKey.get(desiredOldKey);
            if (byDesiredRename != null && !consumed.contains(bizKey.apply(byDesiredRename))) {
                return byDesiredRename;
            }
        }
        Map<String, Object> byObservedRename = obsByOldKey.get(bizKey.apply(d));
        if (byObservedRename != null && !consumed.contains(bizKey.apply(byObservedRename))) {
            return byObservedRename;
        }
        return null;
    }

    /** Consume the matched OBSERVED row and emit an UPDATE when business content differs. */
    private static void pairOrSkip(List<RowChangeDTO> out, MetaTable table, Map<String, Object> d,
            Map<String, Object> prev, List<String> allowList, Function<Map<String, Object>, String> bizKey,
            Set<String> consumed) {
        consumed.add(bizKey.apply(prev));
        List<String> changed = changedKeys(d, prev, allowList);
        if (!changed.isEmpty()) {
            out.add(updated(table, d, prev, changed));
        }
    }

    /** A row's prior business key (its {@code renamedFrom} swapped into the rename key column), or null. */
    private static String oldKey(Map<String, Object> row, Function<Map<String, Object>, String> bizKey,
                                 String renameKeyCol) {
        Object renamedFrom = row.get(RENAMED_FROM);
        if (renameKeyCol == null || renamedFrom == null) {
            return null;
        }
        Map<String, Object> swapped = new HashMap<>(row);
        swapped.put(renameKeyCol, renamedFrom);
        return bizKey.apply(swapped);
    }

    // ----------------------------------------------------------------- row-change builders

    private static RowChangeDTO created(MetaTable table, Map<String, Object> row) {
        RowChangeDTO change = new RowChangeDTO();
        change.setOp(RowChangeOp.CREATE);
        change.setTable(table);
        change.setFullRow(new HashMap<>(row));
        change.setRenamedFrom(str(row.get(RENAMED_FROM)));   // design's captured prior name, if any
        // previousValuesForChangedFields stays empty on CREATE — the DDL gate special-cases CREATE (all columns).
        return change;
    }

    private static RowChangeDTO updated(MetaTable table, Map<String, Object> row,
                                        Map<String, Object> prev, List<String> changedKeys) {
        RowChangeDTO change = new RowChangeDTO();
        change.setOp(RowChangeOp.UPDATE);
        change.setTable(table);
        // No surrogate id / logicalId is threaded — all apply lanes locate by business key
        // (+ renamedFrom bridging the old key). fullRow carries the row's current business key;
        // previousValues carries the matched OBSERVED values for the changed columns.
        change.setFullRow(new HashMap<>(row));
        change.setRenamedFrom(str(row.get(RENAMED_FROM)));   // design's captured prior name, if any
        Map<String, Object> previous = new HashMap<>();
        for (String key : changedKeys) {
            previous.put(key, prev.get(key));
        }
        change.setPreviousValuesForChangedFields(previous);
        return change;
    }

    private static RowChangeDTO deleted(MetaTable table, Map<String, Object> row) {
        RowChangeDTO change = new RowChangeDTO();
        change.setOp(RowChangeOp.DELETE);
        change.setTable(table);
        // No surrogate id — the merger/import locate the row to delete by its business key
        // (fullRow carries the observed row's key).
        change.setFullRow(new HashMap<>(row));
        // previousValuesForChangedFields stays empty on DELETE.
        return change;
    }

    /** Allow-listed attrs where DESIRED and OBSERVED differ (the same set the checksum hashes). */
    private static List<String> changedKeys(Map<String, Object> desired, Map<String, Object> observed,
                                            List<String> allowList) {
        List<String> changed = new ArrayList<>();
        for (String key : allowList) {
            if (!Objects.equals(desired.get(key), observed.get(key))) {
                changed.add(key);
            }
        }
        return changed;
    }

    private static List<Map<String, Object>> normalizeAll(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> raw : rows) {
                out.add(normalize(raw));
            }
        }
        return out;
    }

    /** Normalize a row to a common value shape (enum⇄code string, Integer⇄Long) via one JSON round-trip. */
    private static Map<String, Object> normalize(Map<String, Object> row) {
        return JsonUtils.jsonNodeToObject(JsonUtils.objectToJsonNode(row), ROW_TYPE);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
