package io.softa.starter.permission.spi;

import java.util.List;
import java.util.Set;

/**
 * SPI (2026-07-15「敏感字段集数据倒置」接缝): supplies the raw
 * {@code sensitive_field_set} definitions that {@code SensitiveFieldSetCache}
 * (permission-starter) indexes for the field mask / write guard / Wizard.
 *
 * <p>Inverts the cache's last hard dependency on the RBAC data model: it used
 * to read the {@code SensitiveFieldSet} entity directly (chaining the enforce
 * side onto {@code user-starter}). Now the cache depends only on this contract;
 * {@code user-starter} implements it by enumerating its
 * {@code SensitiveFieldSet} rows.
 *
 * <p>All the indexing / forbidden-field computation stays in
 * {@code SensitiveFieldSetCache}; the impl only hands over per-set raw facts.
 *
 * <p>System-level seed data — read once at startup (application restart is the
 * reload trigger).
 */
public interface SensitiveFieldSetSource {

    /**
     * @return one entry per {@code sensitive_field_set} row; order irrelevant.
     *         Empty list = no sensitive fields defined (mask + write guard are
     *         effectively no-ops).
     */
    List<SensitiveFieldSetDef> getSensitiveFieldSets();

    /**
     * Raw per-set facts, decoupled from the {@code SensitiveFieldSet} entity.
     *
     * @param id         set id (grant key half; {@code PermissionInfo}'s
     *                   {@code modelSensitiveFieldSetsMap} stores these)
     * @param model      the set's canonical model (mask authority); rows with a
     *                   {@code null} id or model are dropped by the cache
     * @param fieldCodes field codes this set covers (the "needs a grant to
     *                   read/write" union contribution for {@code model})
     * @param name       display name for Wizard checkbox labels; may be
     *                   {@code null}
     * @param attachedTo UI-only aggregation hint — models under whose nav rows
     *                   this set should also appear as a Wizard checkbox; does
     *                   NOT affect mask authority; may be {@code null} / empty
     * @param label      the short noun a refusal names the fields by ("IPA" → "IPA fields"); may be
     *                   {@code null}, then {@code name} is used
     */
    record SensitiveFieldSetDef(String id, String model, Set<String> fieldCodes, String name, Set<String> attachedTo,
                                String label) {
        /** A set with no short label: messages name it by {@code name}. */
        public SensitiveFieldSetDef(String id, String model, Set<String> fieldCodes, String name, Set<String> attachedTo) {
            this(id, model, fieldCodes, name, attachedTo, null);
        }
    }
}
