package io.softa.starter.permission.service;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bridges the framework's {@link PermissionService} contract to the
 * user-starter's runtime permission snapshot.
 *
 * <p>Every call:
 * <ol>
 *   <li>Bypasses when there's no bound {@code ContextHolder} scope, when
 *       {@code Context.skipPermissionCheck=true}, or when {@code userId}
 *       is null (bootstrap / async / cron paths).</li>
 *   <li>Loads {@link PermissionInfo} via
 *       {@link PermissionInfoEnricher#enrich} (request-scoped + Redis
 *       cached — repeat calls in one request are free).</li>
 *   <li>Super-admin short-circuits every check.</li>
 *   <li>Delegates rule → SQL translation to {@link ScopeRuleCompiler}
 *       and field-mask resolution to {@link SensitiveFieldSetCache}.</li>
 * </ol>
 *
 * <p>Row-scope fail-closed: models with no entry in
 * {@code modelScopeMap} read through {@link ScopeRuleCompiler#matchNone()}
 * — an empty-tuple {@code IN} leaf rendering {@code WHERE 1=0}, so reads
 * return zero rows. Cross-model relation expansion (Employee →
 * department.name etc.) bypasses this via
 * {@code Context.skipPermissionCheck=true} set by the JDBC pipeline's
 * {@code RelationExpansions} helper, so display-name expansion still works
 * even when the user has no scope on the related model.
 */
@Slf4j
public class PermissionServiceImpl implements PermissionService {

    // @Lazy breaks the init cycle: PermissionServiceImpl ← ModelServiceImpl
    // ← NavigationModelResolverImpl ← PermissionInfoEnricher ← this. Every
    // dependency here is called per-request, never during Spring's bean
    // wiring phase — deferring resolution to first invocation is safe.
    private final PermissionSnapshotProvider snapshotProvider;
    private final ScopeRuleCompiler scopeCompiler;
    private final SensitiveFieldSetCache sfsCache;
    /** ModelService is called back from {@link #checkIdsAccess} to run a
     *  scope-restricted count on the target ids. Framework's {@code count}
     *  routes back through {@code appendScopeAccessFilters}, so the AND-ed
     *  scope makes any out-of-scope id disappear from the count. */
    private final ModelService<?> modelService;
    /** "Which ScopeTypes apply to a model" — lets us tell a truly anchorless
     *  config/extension model (only ALL applies) from real business data that
     *  merely has no grant yet. */
    private final ScopeApplicabilityResolver applicability;

    public PermissionServiceImpl(
            PermissionSnapshotProvider snapshotProvider,
            ScopeRuleCompiler scopeCompiler,
            SensitiveFieldSetCache sfsCache,
            ModelService<?> modelService,
            ScopeApplicabilityResolver applicability) {
        this.snapshotProvider = snapshotProvider;
        this.scopeCompiler = scopeCompiler;
        this.sfsCache = sfsCache;
        this.modelService = modelService;
        this.applicability = applicability;
    }

    // ─────────────────────── row-scope ───────────────────────

    @Override
    public Filters appendScopeAccessFilters(String model, Filters originalFilters) {
        if (shouldBypassDataScope()) return originalFilters;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.isAdmin(pi)) return originalFilters;
        // The company grant bounds every multi-company model, on its own axis: which legal entities a
        // role may reach is a property of the role, so it does not ride the per-model rules below and
        // is not waived by an ALL rule on some model. Admins are already past — a tenant admin sees
        // every company in its tenant.
        originalFilters = appendCompanyGrant(model, originalFilters, pi);
        if (hasExplicitRules(pi, model)) {
            Filters scope = scopeCompiler.compile(rulesFor(pi, model), model);
            if (scope == null) return originalFilters; // ALL rule → no restriction
            return combineAnd(originalFilters, scope);
        }
        // No explicit grant. Real business data (has a forward scope anchor)
        // stays fail-closed; only a truly anchorless config/extension model gets
        // the metadata-derived follow-parent / shared treatment below. (Cross-
        // model display expansion still bypasses everything via skipPermissionCheck.)
        if (hasForwardAnchor(model)) {
            return combineAnd(originalFilters, ScopeRuleCompiler.matchNone());
        }
        Referencer ref = findReferencer(model, pi);
        if (ref == null) {
            return combineAnd(originalFilters, ScopeRuleCompiler.matchNone()); // unreachable
        }
        // Both owned kinds re-enter scope for the parent, so the parent's own row-scope is
        // applied (parent strict ⇒ child strict) — nothing is widened, the child simply
        // inherits the visibility of the row that owns it.
        return switch (ref.kind()) {
            // Shared reference/config (ManyToOne target) → readable.
            case SHARED -> originalFilters;
            // ONE_TO_ONE owned child → the FK sits on the OWNER and holds the child's id,
            // so the visible child ids are the FK values of in-scope owner rows.
            case OWNED_ONE_TO_ONE -> {
                List<Serializable> visible =
                        modelService.getRelatedIds(ref.parentModel(), new Filters(), ref.fkField());
                yield visible.isEmpty()
                        ? combineAnd(originalFilters, ScopeRuleCompiler.matchNone())
                        : combineAnd(originalFilters, Filters.of(ModelConstant.ID, Operator.IN, visible));
            }
            // ONE_TO_MANY child → the FK sits on the CHILD and holds the parent's id, so we
            // constrain that back-reference column against the in-scope parent ids instead
            // of the child's own id.
            case CHILD_BY_BACKREF -> {
                List<?> parents = modelService.getIds(ref.parentModel(), new Filters());
                yield parents.isEmpty()
                        ? combineAnd(originalFilters, ScopeRuleCompiler.matchNone())
                        : combineAnd(originalFilters, Filters.of(ref.fkField(), Operator.IN, parents));
            }
        };
    }

    // ─────────────────────── field mask ───────────────────────

    @Override
    public Collection<String> filterReadableFields(String model, Collection<String> requested, AccessType accessType) {
        if (requested == null || requested.isEmpty() || shouldBypass()) return requested;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.isAdmin(pi)) return requested;
        Set<String> blocked = blockedFields(pi, model);
        if (blocked.isEmpty()) return requested;
        List<String> out = new ArrayList<>(requested.size());
        for (String f : requested) if (!blocked.contains(f)) out.add(f);
        return out;
    }

    @Override
    public <T> T maskResponseValue(String model, T value, AccessType accessType) {
        if (value == null || shouldBypass()) return value;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.isAdmin(pi)) return value;
        Set<String> blocked = blockedFields(pi, model);
        if (blocked.isEmpty()) return value;
        maskInPlace(value, blocked);
        return value;
    }

    private static void maskInPlace(Object value, Set<String> blocked) {
        if (value == null) return;
        if (value instanceof Optional<?> opt) {
            opt.ifPresent(v -> maskInPlace(v, blocked));
            return;
        }
        if (value instanceof Page<?> page) {
            maskInPlace(page.getRows(), blocked);
            return;
        }
        if (value instanceof Collection<?> coll) {
            for (Object el : coll) maskInPlace(el, blocked);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) map;
            for (String f : blocked) {
                if (row.containsKey(f)) row.put(f, null);
            }
        }
        // POJO / primitive → nothing to do here.
    }

    // ─────────────────────── write guard ───────────────────────

    @Override
    public void checkModelFieldsAccess(String model, Collection<String> fields, AccessType accessType) {
        if (fields == null || fields.isEmpty() || shouldBypass()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.isAdmin(pi)) return;
        Set<String> blocked = blockedFields(pi, model);
        if (blocked.isEmpty()) return;
        for (String f : fields) {
            if (blocked.contains(f)) {
                throw new PermissionException(
                        "No " + accessType + " permission for field " + model + "." + f);
            }
        }
    }

    @Override
    public void checkIdsFieldsAccess(String model,
                                     Collection<? extends Serializable> ids,
                                     Set<String> fields,
                                     AccessType accessType) {
        checkModelFieldsAccess(model, fields, accessType);
        checkIdsAccess(model, ids, accessType);
    }

    @Override
    public void checkWritePayload(String model, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty() || shouldBypass()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.isAdmin(pi)) return;
        Set<String> blocked = blockedFields(pi, model);
        if (blocked.isEmpty()) return;
        for (String f : payload.keySet()) {
            if (blocked.contains(f)) {
                throw new PermissionException(
                        "No write permission for field " + model + "." + f);
            }
        }
    }

    // ─────────────────────── model / id / route access ───────────────────────

    @Override
    public void checkModelAccess(String model, AccessType accessType) {
        // Model-level access is enforced by the endpoint gate
        // (PermissionInterceptor) before the request reaches ModelService;
        // duplicating it here would only fire on internal calls where the
        // caller already established authority.
    }

    @Override
    public void checkModelCascadeFieldsAccess(String model,
                                              Map<String, Set<String>> accessModelFields,
                                              AccessType accessType) {
        // Per-model field access on cascade reads is enforced by
        // checkModelFieldsAccess at each JDBC-pipeline sub-read; the
        // aggregate check would duplicate that work.
    }

    @Override
    public void checkIdAccess(String model, Serializable id, AccessType accessType) {
        if (id == null) return;
        checkIdsAccess(model, List.of(id), accessType);
    }

    /**
     * Enforce that every id is within the caller's row-scope.
     *
     * <p>Uses {@link io.softa.framework.orm.service.ModelService#count} which
     * routes back through {@link #appendScopeAccessFilters} — the AND-ed
     * scope makes any out-of-scope id disappear from the count. When the
     * scope-restricted count differs from the caller's id list size, at
     * least one id is either outside the scope OR non-existent; both cases
     * are rejected without distinguishing (to avoid an info-leak channel).
     *
     * <p>Guards the direct-id write paths ({@code deleteByIds} /
     * {@code updateList}) — filter-based writes ({@code updateByFilter} /
     * {@code deleteByFilters}) already flow through {@code getIds} which
     * has scope applied.
     */
    @Override
    public void checkIdsAccess(String model,
                               Collection<? extends Serializable> ids,
                               AccessType accessType) {
        if (ids == null || ids.isEmpty() || shouldBypassDataScope()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.isAdmin(pi)) return;
        List<Serializable> idList = new ArrayList<>(ids);

        // Anchorless config/extension model with no explicit grant carries no
        // scope anchor of its own — verify it via metadata (follow the owner /
        // allow shared config) instead of fail-closing to zero rows.
        if (!hasExplicitRules(pi, model) && !hasForwardAnchor(model)) {
            Referencer ref = findReferencer(model, pi);
            if (ref == null) {
                throw new PermissionException(
                        "Some " + model + " ids are outside your " + accessType + " scope");
            }
            if (ref.kind() == Kind.SHARED) {
                return; // shared reference/config (ManyToOne target) → readable, nothing to check
            }
            if (ref.kind() == Kind.OWNED_ONE_TO_ONE) {
                // The FK is on the OWNER, so the child's own id column cannot be scoped
                // directly — ask the owner instead: "are these ids all referenced by an owner
                // row within my scope?" count() re-enters scope on the owner. Bounded by ids.
                long ownedInScope = modelService.count(ref.parentModel(),
                        Filters.of(ref.fkField(), Operator.IN, idList));
                if (ownedInScope != idList.stream().distinct().count()) {
                    throw new PermissionException(
                            "Some " + model + " ids are outside your " + accessType + " scope");
                }
                return;
            }
            // CHILD_BY_BACKREF falls through to the generic check below: the FK is on the
            // child, so its own read scope already resolves to "back-reference lands on an
            // in-scope parent" (see appendScopeAccessFilters) — counting visible rows by id
            // is exactly the right question, and re-deriving it here would duplicate it.
        }

        long visible = modelService.count(model, Filters.of(ModelConstant.ID, Operator.IN, idList));
        if (visible != idList.size()) {
            throw new PermissionException(
                    "Some " + model + " ids are outside your " + accessType + " scope");
        }
    }

    @Override
    public void checkRouteAccess(String route) {
        // Navigation visibility is enforced by the endpoint gate; the frontend
        // hydrates the sidebar via /me endpoints that already reflect the
        // user's visible nav set.
    }

    @Override
    public Set<String> getUserBlockedModelFields(String model, AccessType accessType) {
        if (shouldBypass()) return Set.of();
        PermissionInfo pi = currentPi();
        if (PermissionInfo.isAdmin(pi)) return Set.of();
        return blockedFields(pi, model);
    }

    // ─────────────────────── helpers ───────────────────────

    private static boolean shouldBypass() {
        if (!ContextHolder.existContext()) return true;
        Context ctx = ContextHolder.getContext();
        return ctx.isSkipPermissionCheck() || ctx.getUserId() == null;
    }

    /**
     * Row-scope bypass = the full bypass PLUS the narrow {@code skipDataScope}
     * flag. Consulted ONLY by the two row-scope entry points
     * ({@code appendScopeAccessFilters} / {@code checkIdsAccess}) — the
     * field-level guards (masking, write payload) keep using
     * {@link #shouldBypass()} so a main-model-scope bypass never turns off
     * sensitive-field protection.
     */
    private static boolean shouldBypassDataScope() {
        if (shouldBypass()) return true;
        return ContextHolder.getContext().isSkipDataScope();
    }

    private PermissionInfo currentPi() {
        Context ctx = ContextHolder.getContext();
        return snapshotProvider.get(ctx.getTenantId(), ctx.getUserId());
    }

    private static List<ScopeRule> rulesFor(PermissionInfo pi, String model) {
        if (pi == null || pi.getModelScopeMap() == null) return null;
        return pi.getModelScopeMap().get(model);
    }

    private Set<String> blockedFields(PermissionInfo pi, String model) {
        if (pi == null) return Set.of();
        Set<String> granted = pi.getModelSensitiveFieldSetsMap() == null
                ? Set.of()
                : pi.getModelSensitiveFieldSetsMap().getOrDefault(model, Set.of());
        return sfsCache.computeForbiddenFields(model, granted);
    }

    private static Filters combineAnd(Filters original, Filters scope) {
        if (original == null || Filters.isEmpty(original)) return scope;
        return Filters.and(original, scope);
    }

    // ───────── metadata-derived scope for anchorless related models ─────────

    /**
     * AND the role's legal-entity grant onto a multi-company model's read.
     *
     * <p>Reads the anchor from the model's metadata rather than assuming a field name, so a model that
     * reaches its company through another one — a per-department statistic, whose companyField is
     * {@code deptId.legalEntityId} — is bounded by the same grant as a model that owns the column.
     *
     * <p>An empty grant means unrestricted. That is the opt-in convention, not an oversight: the
     * alternative empties every screen for every role that predates this table.
     *
     * <p>Composes with the header selection by construction. {@code ModelServiceImpl.scopedAccess}
     * applies that selection to the filters it passes in here, so by this point the caller's filters
     * already carry {@code companyField = <selected>} and this ANDs {@code IN (<granted>)} on top:
     * the selection narrows within the grant, and since the switcher only offers granted entities the
     * result is one company rather than nothing.
     */
    // Package-private for test: the empty-grant default and the path-anchored case both fail silently.
    Filters appendCompanyGrant(String model, Filters filters, PermissionInfo pi) {
        Set<Long> granted = pi == null ? null : pi.getGrantedCompanyIds();
        if (granted == null) {
            return filters;   // no company axis configured → unrestricted
        }
        if (model == null || !ModelManager.existModel(model)) {
            return filters;
        }
        // The company model itself is bounded by its own id. It is deliberately NOT multiCompany
        // (self-scoping is rejected at boot: it would reduce the switcher to the company already
        // selected), so without this branch the grant would never reach the one list that most needs
        // it — the switcher would keep offering companies the role cannot reach, and picking one would
        // AND an ungranted selection against the grant and silently empty every screen.
        String companyField = ModelConstant.COMPANY_MODEL.equals(model)
                ? ModelConstant.ID
                : companyAnchorOf(model);
        if (companyField == null) {
            return filters;
        }
        if (Filters.containsField(filters, ModelConstant.ID)) {
            // Same exemption the selection makes: a by-id read is a display expansion or a cascade
            // resolving a stored value, and blanking a label is not the same as denying access to data.
            return filters;
        }
        if (granted.isEmpty()) {
            // Configured to reach no company — distinct from unconfigured, handled above. Matching
            // nothing is the point: a role written this way (a self-service employee role) must not
            // see a multi-company row, and its own row scope is what still lets it see itself.
            return combineAnd(filters, ScopeRuleCompiler.matchNone());
        }
        // Sorted so the same grant renders the same SQL every time — set iteration order would vary
        // the statement text between requests and defeat statement caching.
        List<Serializable> ids = new ArrayList<>(granted);
        ids.sort(null);
        return combineAnd(filters, Filters.of(companyField, Operator.IN, ids));
    }

    /**
     * The anchor a multi-company model reaches its company through, or null when it is not one.
     *
     * <p>The field name is fixed ({@link ModelConstant#COMPANY_FIELD}) and asserted at init, so this
     * only has to answer whether the model is on the company axis at all.
     */
    private String companyAnchorOf(String model) {
        return ModelManager.getModel(model).isMultiCompany() ? ModelConstant.COMPANY_FIELD : null;
    }

    private boolean hasExplicitRules(PermissionInfo pi, String model) {
        List<ScopeRule> r = rulesFor(pi, model);
        return r != null && !r.isEmpty();
    }

    /**
     * Scope types that apply to <b>every</b> model, so their presence says nothing about
     * whether a model carries a scope anchor of its own: {@code ALL} / {@code CUSTOM} are
     * declared {@code appliesToAll} in the DataScopeType registry, and {@code CREATED_BY_SELF}
     * keys on {@code createdId} — a column {@code AuditableModel} puts on every table.
     *
     * <p>Counting them made {@link #hasForwardAnchor} true for every model, which made the
     * anchorless follow-the-owner fallback below unreachable: every by-id read of a model with
     * no explicit grant fail-closed to zero rows / 403, even for a child row the caller reaches
     * through a parent it can see.
     */
    private static final Set<ScopeType> UNIVERSAL_SCOPE_TYPES =
            EnumSet.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.CREATED_BY_SELF);

    /** A model has a forward scope anchor when some NON-universal ScopeType applies
     *  (a dept / employee / … field the contributors can filter on). A model where only
     *  the universal types apply is structurally unscopable on its own — it is reached
     *  through a parent instead (see {@link #findReferencer}). */
    private boolean hasForwardAnchor(String model) {
        for (ScopeType type : applicability.applicableFor(model)) {
            if (!UNIVERSAL_SCOPE_TYPES.contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * How an anchorless model is reachable from the caller's GRANTED models,
     * derived purely from metadata (no hard-coded model names): scan the granted
     * models' relation fields for one pointing at {@code childModel}.
     * <ul>
     *   <li>a {@code ONE_TO_ONE} owner → follow that owner's row-scope
     *       ({@link Kind#OWNED_ONE_TO_ONE}); the FK lives on the PARENT and holds the
     *       child's id;</li>
     *   <li>a {@code ONE_TO_MANY} parent → follow that parent's row-scope
     *       ({@link Kind#CHILD_BY_BACKREF}); the FK lives on the CHILD
     *       ({@code relatedField}) and holds the parent's id — the inverse direction,
     *       so the two cannot share one filter shape;</li>
     *   <li>else a {@code MANY_TO_ONE} referrer → shared reference/config
     *       ({@link Kind#SHARED});</li>
     *   <li>none → {@code null} (not reachable from any grant → stays fail-closed).</li>
     * </ul>
     *
     * <p>Both owned kinds match what the role wizard already promises: {@code
     * NavigationConfigOptionsController} deliberately does NOT surface OneToOne /
     * OneToMany children as separately grantable models, precisely because they are
     * "bounded by the parent's scope". This resolves that contract at runtime.
     *
     * <p>Priority ONE_TO_ONE &gt; ONE_TO_MANY &gt; MANY_TO_ONE: an ownership edge is a
     * tighter statement than a shared reference, so it wins when a model is reachable
     * both ways.
     */
    private Referencer findReferencer(String childModel, PermissionInfo pi) {
        if (pi.getModelScopeMap() == null) return null;
        Referencer backRef = null;
        Referencer shared = null;
        for (String granted : pi.getModelScopeMap().keySet()) {
            if (!ModelManager.existModel(granted)) continue;
            for (MetaField f : ModelManager.getModelFields(granted)) {
                if (!childModel.equals(f.getRelatedModel())) continue;
                if (f.getFieldType() == FieldType.ONE_TO_ONE) {
                    return new Referencer(granted, f.getFieldName(), Kind.OWNED_ONE_TO_ONE);
                }
                if (f.getFieldType() == FieldType.ONE_TO_MANY && backRef == null
                        && StringUtils.isNotBlank(f.getRelatedField())) {
                    backRef = new Referencer(granted, f.getRelatedField(), Kind.CHILD_BY_BACKREF);
                }
                if (f.getFieldType() == FieldType.MANY_TO_ONE && shared == null) {
                    shared = new Referencer(granted, f.getFieldName(), Kind.SHARED);
                }
            }
        }
        return backRef != null ? backRef : shared;
    }

    /** How a granted parent reaches an anchorless child — decides which filter shape applies. */
    private enum Kind {
        /** FK on the parent, holding the child's id (ONE_TO_ONE). */
        OWNED_ONE_TO_ONE,
        /** FK on the child, holding the parent's id (ONE_TO_MANY back-reference). */
        CHILD_BY_BACKREF,
        /** Shared reference / config the parent merely points at (MANY_TO_ONE). */
        SHARED
    }

    /** {@code fkField} is on the parent for {@link Kind#OWNED_ONE_TO_ONE} / {@link Kind#SHARED},
     *  and on the child for {@link Kind#CHILD_BY_BACKREF}. */
    private record Referencer(String parentModel, String fkField, Kind kind) {}
}
