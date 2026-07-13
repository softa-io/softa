package io.softa.starter.studio.release.dto;

import java.util.List;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.starter.metadata.checksum.AggregateChecksum;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignModelIndex;
import io.softa.starter.studio.meta.entity.DesignOptionItem;
import io.softa.starter.studio.meta.entity.DesignOptionSet;

/**
 * THE descriptor of the five swept {@code design_*} meta-tables — the single source for their
 * topology, which was previously re-encoded per consumer (differ / merger / importer / cloner /
 * env-delete cascade / DTO grouping):
 * <ul>
 *   <li><b>identity</b> — the design entity ({@link #designName()} keys {@code ModelService} calls and
 *       the diff row-groups) and its wire {@link #table()} ({@link MetaTable}, which also carries the
 *       runtime {@code Sys*} counterpart);</li>
 *   <li><b>business key</b> — {@link #bizKeyAttrs()}, the per-env natural identity every lane pairs and
 *       locates rows by (never a surrogate id; {@code envId} is the query scope, not part of the key);</li>
 *   <li><b>aggregate shape</b> — {@link #parent()} plus the child→parent link: {@link #parentFkAttr()}
 *       (the per-env surrogate FK, re-pointed on clone/merge/import, never carried across envs) and
 *       {@link #parentCodeAttr()} (the denormalized parent business code the re-pointing resolves by);</li>
 *   <li><b>rename bridge</b> — {@link #renameBridgeAttr()}: the key column {@code renamedFrom} swaps, set
 *       ONLY for field / option-item. A model / option-set / index rename is never bridged: parents carry
 *       children (bridging would rename the table yet leave the children diffing against it), so they pair
 *       by current business key only → drop+add, gated downstream;</li>
 *   <li><b>compared attrs</b> — {@link #checksumAttrs()}, the checksum allow-list "changed" is decided on;</li>
 *   <li><b>order</b> — declaration order is parents-first, so {@link #values()} is the FK-safe apply/create
 *       order and {@link #deleteOrder()} (its reverse) the FK-safe delete order.</li>
 * </ul>
 * Expanding the desired-state sweep (translations / views / navigation) adds a constant here — plus the
 * {@link io.softa.starter.studio.release.desired.DesignRows} shape, {@link MetaTable}, checksum attrs and
 * connector read/apply support that a new table inherently needs.
 */
public enum DesignAggregate {

    MODEL(MetaTable.MODEL, DesignModel.class,
            List.of(MetaKeys.MODEL_NAME),
            null, null, null, null, AggregateChecksum.MODEL_ATTRS),

    OPTION_SET(MetaTable.OPTION_SET, DesignOptionSet.class,
            List.of(MetaKeys.OPTION_SET_CODE),
            null, null, null, null, AggregateChecksum.OPTION_SET_ATTRS),

    FIELD(MetaTable.FIELD, DesignField.class,
            List.of(MetaKeys.MODEL_NAME, MetaKeys.FIELD_NAME),
            MODEL, LambdaUtils.getAttributeName(DesignField::getModelId), MetaKeys.MODEL_NAME,
            MetaKeys.FIELD_NAME, AggregateChecksum.FIELD_ATTRS),

    INDEX(MetaTable.INDEX, DesignModelIndex.class,
            List.of(MetaKeys.MODEL_NAME, MetaKeys.INDEX_NAME),
            MODEL, LambdaUtils.getAttributeName(DesignModelIndex::getModelId), MetaKeys.MODEL_NAME,
            null, AggregateChecksum.INDEX_ATTRS),

    OPTION_ITEM(MetaTable.OPTION_ITEM, DesignOptionItem.class,
            List.of(MetaKeys.OPTION_SET_CODE, MetaKeys.ITEM_CODE),
            OPTION_SET, LambdaUtils.getAttributeName(DesignOptionItem::getOptionSetId), MetaKeys.OPTION_SET_CODE,
            MetaKeys.ITEM_CODE, AggregateChecksum.OPTION_ITEM_ATTRS);

    /** FK-safe delete order: children before parents (the reverse of the parents-first declaration). */
    private static final List<DesignAggregate> DELETE_ORDER = List.of(values()).reversed();

    private final MetaTable table;
    private final Class<?> entityClass;
    private final List<String> bizKeyAttrs;
    private final DesignAggregate parent;
    private final String parentFkAttr;
    private final String parentCodeAttr;
    private final String renameBridgeAttr;
    private final List<String> checksumAttrs;

    DesignAggregate(MetaTable table, Class<?> entityClass, List<String> bizKeyAttrs,
                    DesignAggregate parent, String parentFkAttr, String parentCodeAttr,
                    String renameBridgeAttr, List<String> checksumAttrs) {
        this.table = table;
        this.entityClass = entityClass;
        this.bizKeyAttrs = bizKeyAttrs;
        this.parent = parent;
        this.parentFkAttr = parentFkAttr;
        this.parentCodeAttr = parentCodeAttr;
        this.renameBridgeAttr = renameBridgeAttr;
        this.checksumAttrs = checksumAttrs;
    }

    /** The wire meta-table (also carries the runtime {@code Sys*} entity — see {@link MetaTable}). */
    public MetaTable table() {
        return table;
    }

    /** The design meta-model simple name — the {@code ModelService} model name and diff group key. */
    public String designName() {
        return entityClass.getSimpleName();
    }

    /** The per-env business-key attrs (composite for children; {@code envId} is scope, not key). */
    public List<String> bizKeyAttrs() {
        return bizKeyAttrs;
    }

    /** The owning aggregate root, or {@code null} for a root (model / option-set). */
    public DesignAggregate parent() {
        return parent;
    }

    /** The child→parent surrogate FK attr (per-env identity), or {@code null} for a root. */
    public String parentFkAttr() {
        return parentFkAttr;
    }

    /** The denormalized parent business-code attr the FK re-pointing resolves by, or {@code null} for a root. */
    public String parentCodeAttr() {
        return parentCodeAttr;
    }

    /** The key column a {@code renamedFrom} bridge swaps — field / option-item only, else {@code null}. */
    public String renameBridgeAttr() {
        return renameBridgeAttr;
    }

    /** The checksum allow-list "changed" is decided on (the same attrs the aggregate checksum hashes). */
    public List<String> checksumAttrs() {
        return checksumAttrs;
    }

    /** FK-safe delete order: children before parents. */
    public static List<DesignAggregate> deleteOrder() {
        return DELETE_ORDER;
    }

    /** The aggregate for a wire {@link MetaTable}. */
    public static DesignAggregate of(MetaTable table) {
        for (DesignAggregate aggregate : values()) {
            if (aggregate.table == table) {
                return aggregate;
            }
        }
        throw new IllegalArgumentException("No design aggregate for meta table: " + table);
    }
}
