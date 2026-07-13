package io.softa.starter.studio.release.desired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared row primitives for materializing one env's {@code design_*} rows into another env, and for
 * locating / re-parenting them by business key. Used by {@link DesignEnvCloner} (full clone),
 * {@link DesignEnvMerger} (env↔env diff-merge) and {@link DesignDriftImporter} (runtime→design import):
 * a row destined for the target env must drop its source surrogate {@code id} and audit trail (the create
 * pipeline mints fresh ones) and be stamped with the target {@code envId}, and every locate / re-parent
 * resolves through the per-env <b>business key</b> — never a surrogate id threaded across envs.
 * <p>
 * Parent-FK remap has two flavours the callers own: clone/merge build the parent business-key → new id map
 * incrementally as parents are created; import queries the parents already in the target env. Both then
 * apply {@link #relinkChildFk} to point each child's FK at the target-env parent with the same business code.
 */
final class DesignEnvRowOps {

    static final String ID = "id";
    static final String ENV_ID = "envId";

    /**
     * Composite-key delimiter — a control char that cannot appear in a business code, so a multi-attr
     * business key is unambiguous.
     */
    static final String KEY_SEP = "\u0001";

    /** Audit columns are re-stamped by the create pipeline; stripped so a clone reads as newly created. */
    private static final List<String> AUDIT_KEYS = List.of(
            "createdTime", "createdBy", "createdId", "updatedTime", "updatedBy", "updatedId");

    private DesignEnvRowOps() {
    }

    /**
     * A clone of {@code src} ready to insert into {@code targetEnvId}: drop the surrogate {@code id}
     * and audit trail, stamp {@code envId}.
     */
    static Map<String, Object> prepareClone(Map<String, Object> src, Long targetEnvId) {
        Map<String, Object> clone = new HashMap<>(src);
        clone.remove(ID);
        AUDIT_KEYS.forEach(clone::remove);
        clone.put(ENV_ID, targetEnvId);
        return clone;
    }

    /** A row's composite business key string (KEY_SEP delimiter — unambiguous across multi-attr keys). */
    static String bizKey(List<String> keyAttrs, Map<String, Object> row) {
        return keyAttrs.stream().map(a -> String.valueOf(row.get(a))).collect(Collectors.joining(KEY_SEP));
    }

    /** A row's PRIOR business key — the {@code renameKeyAttr} value swapped for {@code oldName} (renamedFrom). */
    static String oldBizKey(List<String> keyAttrs, String renameKeyAttr, Map<String, Object> row, String oldName) {
        return keyAttrs.stream()
                .map(a -> a.equals(renameKeyAttr) ? oldName : String.valueOf(row.get(a)))
                .collect(Collectors.joining(KEY_SEP));
    }

    /** Index a row list by its (composite) business key → surrogate id. */
    static Map<String, Long> indexByKey(List<Map<String, Object>> rows, List<String> keyAttrs) {
        Map<String, Long> map = new HashMap<>();
        if (rows == null) {
            return map;
        }
        for (Map<String, Object> row : rows) {
            Long id = asLong(row.get(ID));
            if (id != null) {
                map.put(bizKey(keyAttrs, row), id);
            }
        }
        return map;
    }

    /**
     * Re-point each child row's parent FK ({@code fkAttr}) at the target-env parent that owns the same
     * business code ({@code parentCodeAttr}). A child whose code has no parent in the map gets a null FK.
     */
    static void relinkChildFk(List<Map<String, Object>> rows, String fkAttr, String parentCodeAttr,
                              Map<String, Long> parentIdByCode) {
        for (Map<String, Object> row : rows) {
            Object code = row.get(parentCodeAttr);
            row.put(fkAttr, code == null ? null : parentIdByCode.get(String.valueOf(code)));
        }
    }

    static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
