package io.softa.starter.permission.sensitive;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.starter.permission.spi.SensitiveFieldSetSource;
import io.softa.starter.permission.spi.SensitiveFieldSetSource.SensitiveFieldSetDef;

/**
 * In-memory snapshot of {@code sensitive_field_set} rows, indexed for
 * O(1) lookups by both consumers in the user-starter sensitive-field
 * stack:
 *
 * <ul>
 *   <li>{@link FieldFilter} — response field mask
 *       ({@code @RestControllerAdvice}).</li>
 *   <li>{@link FieldWriteGuardAspect} — request-side write guard that
 *       rejects blocked fields (AOP around ModelService write methods).</li>
 * </ul>
 *
 * <p>The cache fills at {@link PostConstruct} — once, before the embedded
 * server starts accepting requests. {@code sensitive_field_set} is system-
 * level seed data, and re-applying the platform seeds reloads it on every
 * instance ({@code PlatformSeedSyncedListener}). A reload that cannot read
 * the rows throws before anything is swapped, so the previous sets stay.
 *
 * <p>{@code @PostConstruct} (not {@code @EventListener(ApplicationReadyEvent)})
 * matters because {@code ApplicationReadyEvent} fires AFTER the server is
 * already accepting requests — leaving a brief fail-OPEN window where
 * the mask + write guard would see an empty {@code allSensitiveByModel}
 * and skip masking / write rejection. {@code @PostConstruct} runs during
 * bean init (before the web context exposes endpoints), closing that
 * window.
 *
 * <p>Why this is a separate component (not inline on FieldFilter): so the
 * read advice and the write aspect read the SAME snapshot. Splitting the
 * load into two bean lifecycles risked drift when reloads existed; keeping
 * it shared remains the right structure even with a single startup load
 * because the data shape (per-model union + setId expansion) is the same
 * for both consumers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveFieldSetCache {

    private final SensitiveFieldSetSource source;

    /** model → set of all field codes covered by ANY sensitive_field_set
     *  on that model. The "needs grant to read/write" union — both layers
     *  consume this. */
    private final AtomicReference<Map<String, Set<String>>> allSensitiveByModel =
            new AtomicReference<>(Map.of());

    /** setId → set of field codes (raw expansion table). Used to translate
     *  a user's {@code modelSensitiveFieldSetsMap} grant ({@code Set<setId>})
     *  into the actual field-code allow-list.
     *
     *  <p>Wrapped in {@link AtomicReference} for the same reason as the other
     *  swap-on-reload maps: callers iterate / look up concurrently with
     *  {@link #reload}, so we publish a fresh immutable snapshot atomically
     *  instead of mutating a shared {@code ConcurrentHashMap} in-place. */
    private final AtomicReference<Map<String, Set<String>>> setIdToFieldCodes =
            new AtomicReference<>(Map.of());

    /** setId → model. Lets the consumer ignore grants that nominally exist
     *  but point at a different model than the current request's target. */
    private final AtomicReference<Map<String, String>> setIdToModel =
            new AtomicReference<>(Map.of());

    /** setId → display name. Used by the Wizard endpoint
     *  ({@code NavigationConfigOptionsController}) so the admin UI can render
     *  SFS checkbox labels without re-querying the {@code sensitive_field_set}
     *  table on every wizard open. Reading from cache also avoids the chicken-
     *  and-egg where admins without a scope grant on {@code SensitiveFieldSet}
     *  see an empty SFS list. */
    private final AtomicReference<Map<String, String>> setIdToName =
            new AtomicReference<>(Map.of());

    /** attachmentModel → list of setIds that declared {@code attachedTo}
     *  containing that model. Powers Wizard "extra rows" so a SFS bound to
     *  {@code EmpBankAccount} but UI-aggregated under {@code Employee}
     *  appears as a checkbox on the Employee nav row.
     *
     *  <p>NOT consumed by mask / write-guard — those still use {@link
     *  #setIdToModel} (the SFS's canonical {@code model}) as the authority. */
    private final AtomicReference<Map<String, Set<String>>> setIdsByAttachedModel =
            new AtomicReference<>(Map.of());

    @PostConstruct
    public void reload() {
        List<SensitiveFieldSetDef> sets;
        try {
            sets = source.getSensitiveFieldSets();
        } catch (Throwable t) {
            // Fail-LOUD, not fail-OPEN. Previously we caught and left the
            // caches empty — the field mask (`hasSensitiveFieldsOn` returns
            // false for every model) and write guard (same signal) both
            // silently turn OFF, so every sensitive field becomes readable
            // and writable for the lifetime of this pod (until the next
            // redeploy — {@code sensitive_field_set} is seed data with no
            // runtime reload).
            //
            // Throwing here causes Spring bean init to fail → pod fails its
            // readiness probe → doesn't accept traffic → K8s rolls it back
            // or the operator restarts once the DB is reachable. Other pods
            // that loaded successfully keep serving. This is the correct
            // defense-in-depth: a DB flap during rolling deploy must NOT
            // leave a working-looking pod with sensitive-field enforcement
            // permanently disabled.
            log.error("SensitiveFieldSetCache.reload — failed to load sensitive_field_set rows; "
                    + "refusing to start with an empty cache (would disable the field mask + write guard). "
                    + "Fix the DB / migration and restart.", t);
            throw new IllegalStateException(
                    "SensitiveFieldSetCache load failed — cannot start with sensitive-field enforcement disabled", t);
        }
        Map<String, Set<String>> byModel = new HashMap<>();
        Map<String, Set<String>> bySetId = new HashMap<>();
        Map<String, String> setToModel = new HashMap<>();
        Map<String, String> setToName = new HashMap<>();
        Map<String, Set<String>> byAttached = new HashMap<>();
        for (SensitiveFieldSetDef s : sets) {
            if (s.id() == null || s.model() == null) continue;
            Set<String> codes = s.fieldCodes() == null ? new HashSet<>() : new HashSet<>(s.fieldCodes());
            bySetId.put(s.id(), codes);
            setToModel.put(s.id(), s.model());
            if (s.name() != null) setToName.put(s.id(), s.name());
            byModel.computeIfAbsent(s.model(), k -> new HashSet<>()).addAll(codes);
            // Index UI attachment hints. attachedTo lets a SFS bound to
            // model A appear as a Wizard option under nav rows whose
            // primary model is B — does NOT affect mask authority.
            if (s.attachedTo() != null) {
                for (String attached : s.attachedTo()) {
                    if (attached == null || attached.isEmpty()) continue;
                    byAttached.computeIfAbsent(attached, k -> new HashSet<>()).add(s.id());
                }
            }
        }
        // Atomic swap — readers see either the old map or the new map fully,
        // never a half-populated state.
        allSensitiveByModel.set(Map.copyOf(byModel));
        setIdToFieldCodes.set(Map.copyOf(bySetId));
        setIdToModel.set(Map.copyOf(setToModel));
        setIdToName.set(Map.copyOf(setToName));
        setIdsByAttachedModel.set(Map.copyOf(byAttached));
        log.info("SensitiveFieldSetCache — loaded {} sensitive_field_set defs across {} models ({} attachment hints)",
                sets.size(), byModel.size(), byAttached.size());
    }

    /** Display name of a {@code setId}, or null if unknown. Powers Wizard
     *  UI rendering. */
    public String nameOf(String setId) {
        if (setId == null) return null;
        return setIdToName.get().get(setId);
    }

    /** All setIds whose canonical {@code model} equals {@code modelName}.
     *  Powers Wizard UI for the "own model" SFS rows on a nav whose primary
     *  model is {@code modelName}. */
    public Set<String> setIdsOwnedBy(String modelName) {
        if (modelName == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : setIdToModel.get().entrySet()) {
            if (modelName.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** All field codes that ANY sensitive_field_set covers on this model.
     *  Empty/missing → no sensitive fields registered (model unrestricted). */
    public Set<String> allSensitiveFieldsOn(String modelName) {
        if (modelName == null) return Set.of();
        Set<String> codes = allSensitiveByModel.get().get(modelName);
        return codes == null ? Set.of() : codes;
    }

    /** True iff this model has at least one sensitive_field_set defined.
     *  Hot-path optimization: callers skip the user-grant lookup entirely
     *  when the model has no sensitive fields. */
    public boolean hasSensitiveFieldsOn(String modelName) {
        if (modelName == null) return false;
        Set<String> codes = allSensitiveByModel.get().get(modelName);
        return codes != null && !codes.isEmpty();
    }

    /** Canonical {@code model} of a {@code setId}, or null if the set is
     *  unknown. Used by {@code PermissionInfoEnricher} (user-starter)
     *  to route grants into {@code modelSensitiveFieldSetsMap} keyed by the
     *  SFS's own model (not the granting nav's primary model), so a SFS
     *  attached to multiple nav rows still resolves correctly at mask time. */
    public String modelOf(String setId) {
        if (setId == null) return null;
        return setIdToModel.get().get(setId);
    }

    /** All setIds whose {@code attachedTo} declares {@code modelName}.
     *  Powers Wizard UI aggregation — these SFS appear as additional
     *  rows under nav whose primary model is {@code modelName}, in
     *  addition to the SFS that own that model directly. Empty/missing
     *  → no UI attachments declared for this model. */
    public Set<String> setIdsAttachedTo(String modelName) {
        if (modelName == null) return Set.of();
        Set<String> ids = setIdsByAttachedModel.get().get(modelName);
        return ids == null ? Set.of() : ids;
    }

    /** Translate granted {@code setIds} to the union of their field codes,
     *  filtered to only those sets bound to {@code modelName}. Sets bound
     *  to a different model are silently ignored — admin grants are stored
     *  per (model, setId) tuple, but the PermissionInfo flattens them per
     *  model, so a stray grant on a different model can't escape its model
     *  scope here. */
    public Set<String> grantedFieldsFor(String modelName, Set<String> grantedSetIds) {
        if (grantedSetIds == null || grantedSetIds.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        // Snapshot once — avoid two reads racing with a concurrent reload.
        Map<String, String> models = setIdToModel.get();
        Map<String, Set<String>> codesBySet = setIdToFieldCodes.get();
        for (String sid : grantedSetIds) {
            if (!modelName.equals(models.get(sid))) continue;
            Set<String> codes = codesBySet.get(sid);
            if (codes != null) out.addAll(codes);
        }
        return out;
    }

    /**
     * Per-model "fields the caller does NOT have access to" = all sensitive
     * field codes on the model − granted set's field codes. Used by both
     * the field mask ({@link FieldFilter} — what to mask in the response)
     * and the write guard ({@link FieldWriteGuardAspect} — what to reject
     * on write).
     *
     * <p>Returns empty set when the model registers no sensitive fields,
     * or when the granted sets cover everything sensitive on the model.
     * Hot-path callers can short-circuit on an empty result without doing
     * any per-row work.
     */
    public Set<String> computeForbiddenFields(String modelName, Set<String> grantedSetIds) {
        if (!hasSensitiveFieldsOn(modelName)) return Set.of();
        Set<String> all = allSensitiveFieldsOn(modelName);
        Set<String> granted = grantedFieldsFor(modelName, grantedSetIds);
        if (granted.isEmpty()) return all.isEmpty() ? Set.of() : new HashSet<>(all);
        Set<String> diff = new HashSet<>(all);
        diff.removeAll(granted);
        return diff;
    }

}
