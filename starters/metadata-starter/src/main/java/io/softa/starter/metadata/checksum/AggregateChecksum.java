package io.softa.starter.metadata.checksum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.softa.framework.base.security.HashUtils;
import io.softa.starter.metadata.catalog.SysCatalog;
import io.softa.starter.metadata.catalog.SysColumn;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.entity.SysOptionSet;

/**
 * Two-level Merkle checksum of a metadata aggregate.
 *
 * <p>A <b>Model</b> aggregate = the model's key attrs + all its Fields + all its
 * Indexes; an <b>OptionSet</b> aggregate = the set's key attrs + all its Items.
 * Each child's hash is SHA-256 of its canonical form; the aggregate hash is
 * SHA-256 of the root's canonical form plus the (sorted) child hashes. Comparing
 * a single aggregate hash tells you whether anything anywhere in the aggregate
 * changed, and the design ({@code design_*}) and runtime ({@code sys_*}) sides
 * compute the identical hash for identical semantic state — the gate that lets a
 * deploy ship full rows only for aggregates whose checksum differs.
 *
 * <p>The attribute set is {@link SysCatalog}'s keys + data columns — the SAME set
 * {@code DiffEngine} compares — so a checksum-change and a diff-change can never
 * disagree, and there is no parallel field list to drift. Excluded (never affect
 * runtime schema/behavior): surrogate {@code id}, {@code appCode},
 * {@code active}/{@code deleted} runtime state, and
 * {@code TO_MANY} relations — all dropped by {@code SysCatalog}'s own EXCLUDED set.
 *
 * <p>Computed on demand (never stored): the catalog is small and in-memory, so a
 * stored column would only add staleness risk and write-path coupling for no real
 * saving. Pure / stateless — no Spring, no DB.
 */
public final class AggregateChecksum {

    private AggregateChecksum() {
    }

    // The attribute set IS SysCatalog's keys + data — the SAME set DiffEngine compares
    // (equalByCatalog), so a checksum-change and a diff-change can never disagree, and
    // adding a @Field to the entity is picked up automatically here (single source, no
    // parallel list to maintain). Exclusions (id / appCode / active / deleted
    // and TO_MANY relations) drop out via SysCatalog's own EXCLUDED + TO_MANY skip. Sys*
    // is the schema authority; studio Design* aggregates are projected onto these same
    // attribute names (pinned by CrossLaneChecksumGoldenTest).

    /** Model root key attributes. */
    public static final List<String> MODEL_ATTRS = attrsOf(SysModel.class);

    /** Field child key attributes. */
    public static final List<String> FIELD_ATTRS = attrsOf(SysField.class);

    /** Index child key attributes (refinement #2 — index is part of the Model aggregate). */
    public static final List<String> INDEX_ATTRS = attrsOf(SysModelIndex.class);

    /** OptionSet root key attributes. */
    public static final List<String> OPTION_SET_ATTRS = attrsOf(SysOptionSet.class);

    /** OptionItem child key attributes. */
    public static final List<String> OPTION_ITEM_ATTRS = attrsOf(SysOptionItem.class);

    private static List<String> attrsOf(Class<?> type) {
        SysCatalog.SysTable<?> t = SysCatalog.of(type);
        return Stream.concat(t.keys().stream(), t.data().stream()).map(SysColumn::name).toList();
    }

    /**
     * Checksum of a Model aggregate: the model row + all its fields + all its indexes.
     * Field and index hashes are kept in separate groups so a field and an index can
     * never alias.
     */
    public static String ofModel(Map<String, Object> model,
                                 List<Map<String, Object>> fields,
                                 List<Map<String, Object>> indexes) {
        String root = CanonicalMetadataSerializer.canonical(model, MODEL_ATTRS);
        return HashUtils.sha256Hex(root
                + "|F" + childGroup(fields, FIELD_ATTRS)
                + "|I" + childGroup(indexes, INDEX_ATTRS));
    }

    /**
     * Checksum of an OptionSet aggregate: the set row + all its items.
     */
    public static String ofOptionSet(Map<String, Object> optionSet,
                                     List<Map<String, Object>> items) {
        String root = CanonicalMetadataSerializer.canonical(optionSet, OPTION_SET_ATTRS);
        return HashUtils.sha256Hex(root + "|I" + childGroup(items, OPTION_ITEM_ATTRS));
    }

    /**
     * Hash one child in isolation — exposed for callers that want to diff child-by-child
     * after an aggregate-level mismatch.
     */
    public static String ofChild(Map<String, Object> child, List<String> allowList) {
        return HashUtils.sha256Hex(CanonicalMetadataSerializer.canonical(child, allowList));
    }

    private static String childGroup(List<Map<String, Object>> children, List<String> allowList) {
        if (children == null || children.isEmpty()) {
            return "[]";
        }
        List<String> hashes = new ArrayList<>(children.size());
        for (Map<String, Object> child : children) {
            hashes.add(ofChild(child, allowList));
        }
        Collections.sort(hashes);   // child order never affects the aggregate hash
        return "[" + String.join("|", hashes) + "]";
    }
}
