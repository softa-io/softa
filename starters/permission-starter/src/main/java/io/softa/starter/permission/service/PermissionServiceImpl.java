package io.softa.starter.permission.service;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.domain.CreateAccess;
import io.softa.framework.orm.domain.AggFunctions;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.domain.RecordAccess;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.meta.MetaField;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.AccessScope;
import io.softa.framework.orm.service.ImportScope;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.RoleGrant;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.scope.SubtreeFilterRewriter;
import io.softa.starter.permission.scope.ModelDefaultScopeRegistry;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

    /** The endpoint gate's own index, asked directly by {@link #hasModelActionGrant} for the endpoints
     *  it cannot reach by URL. Resolved through a supplier, not injected directly, and deliberately:
     *  {@link EndpointIndex#init()} reads the permission table at {@code @PostConstruct}, which needs
     *  {@code ModelManager} already loaded. Taking the index as a constructor argument made Spring
     *  build it the moment THIS bean is built — before {@code AppStartup} runs {@code ModelManager.init()}
     *  — so the index read an unloaded catalog and came back empty, and every endpoint answered
     *  "Endpoint not registered". Deferring the lookup to first use (a request, long after startup)
     *  keeps the index's construction where it was before file access started asking for it.
     *  Supplier may yield null: a deployment with no index still answers "granted", which is what an
     *  unregistered pair means anyway. */
    private final Supplier<EndpointIndex> endpointIndexSupplier;

    /** Expands `deptField CHILD OF ids` onto Department.idPath — see
     *  {@link io.softa.starter.permission.scope.SubtreeFilterRewriter}. Supplied the same
     *  lazy way as the endpoint index, and for the same reason: the rewriter reads ModelManager, so
     *  resolving it while this bean is built would touch an unloaded catalog. A null supplier (the
     *  older constructors, and every unit test) simply means no rewrite. */
    private final Supplier<SubtreeFilterRewriter> subtreeRewriterSupplier;

    /** Which models declare a fallback row scope. Supplied lazily for the same reason as the two
     *  above — the registry reads through ModelService, which is not resolvable while this bean is
     *  built. A null supplier (the older constructors, and every unit test) means no model declares
     *  one, i.e. exactly the behaviour that predates the mechanism. */
    private final Supplier<ModelDefaultScopeRegistry> defaultScopeSupplier;

    public PermissionServiceImpl(PermissionSnapshotProvider snapshotProvider,
            ScopeRuleCompiler scopeCompiler,
            SensitiveFieldSetCache sfsCache,
            ModelService<?> modelService,
            ScopeApplicabilityResolver applicability) {
        this(snapshotProvider, scopeCompiler, sfsCache, modelService, applicability, () -> null);
    }

    public PermissionServiceImpl(PermissionSnapshotProvider snapshotProvider,
            ScopeRuleCompiler scopeCompiler,
            SensitiveFieldSetCache sfsCache,
            ModelService<?> modelService,
            ScopeApplicabilityResolver applicability,
            Supplier<EndpointIndex> endpointIndexSupplier) {
        this(snapshotProvider, scopeCompiler, sfsCache, modelService, applicability,
                endpointIndexSupplier, () -> null);
    }

    public PermissionServiceImpl(PermissionSnapshotProvider snapshotProvider,
            ScopeRuleCompiler scopeCompiler,
            SensitiveFieldSetCache sfsCache,
            ModelService<?> modelService,
            ScopeApplicabilityResolver applicability,
            Supplier<EndpointIndex> endpointIndexSupplier,
            Supplier<SubtreeFilterRewriter> subtreeRewriterSupplier) {
        this(snapshotProvider, scopeCompiler, sfsCache, modelService, applicability,
                endpointIndexSupplier, subtreeRewriterSupplier, () -> null);
    }

    public PermissionServiceImpl(PermissionSnapshotProvider snapshotProvider,
            ScopeRuleCompiler scopeCompiler,
            SensitiveFieldSetCache sfsCache,
            ModelService<?> modelService,
            ScopeApplicabilityResolver applicability,
            Supplier<EndpointIndex> endpointIndexSupplier,
            Supplier<SubtreeFilterRewriter> subtreeRewriterSupplier,
            Supplier<ModelDefaultScopeRegistry> defaultScopeSupplier) {
        this.snapshotProvider = snapshotProvider;
        this.scopeCompiler = scopeCompiler;
        this.sfsCache = sfsCache;
        this.modelService = modelService;
        this.applicability = applicability;
        this.endpointIndexSupplier = endpointIndexSupplier == null ? () -> null : endpointIndexSupplier;
        this.subtreeRewriterSupplier =
                subtreeRewriterSupplier == null ? () -> null : subtreeRewriterSupplier;
        this.defaultScopeSupplier = defaultScopeSupplier == null ? () -> null : defaultScopeSupplier;
    }

    // ─────────────────────── row-scope ───────────────────────

    @Override
    public Filters appendScopeAccessFilters(String model, Filters originalFilters) {
        // Before every early return below, deliberately. This is a rewrite of what the caller asked
        // for, not a restriction added on top: `CHILD OF` on a department reference has to become an
        // idPath condition or it compiles to a LIKE against an id and matches by coincidence. An
        // admin, and anything running under @SkipPermissionCheck, asks the same question and needs
        // the same answer — placing it after the bypass would leave exactly them with the broken one.
        originalFilters = rewriteScopeFilters(model, originalFilters);
        if (shouldBypass()) return originalFilters;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return originalFilters;
        // A read performed for another action — the count behind "may I update these ids", an
        // export — reaches the rows of the roles holding that action, not every role's.
        AccessType access = AccessScope.current();
        originalFilters = guardSensitiveConditions(pi, model, access, originalFilters);
        Filters scope = rowScope(pi, model, access);
        return scope == null ? originalFilters : combineAnd(originalFilters, scope);
    }

    /**
     * A condition the caller wrote on a sensitive field matches only where the caller may see that
     * field. Otherwise {@code salary > 5000} would sort the rows the caller cannot see the figure on
     * into "above" and "not above" — the value, found out one question at a time.
     *
     * <p>Such a condition is AND-ed with the rows of the roles granting the field, and one on a field
     * no role grants matches nothing. Rows outside are read as if the value were unknown, so they never
     * match — {@code IS NULL} included, which is the conservative reading of "unknown". Only the
     * model's own fields are guarded; a path into another model's sensitive field is not.
     */
    private Filters guardSensitiveConditions(PermissionInfo pi, String model, AccessType access, Filters filters) {
        if (filters == null || Filters.isEmpty(filters)) return filters;
        if (sfsCache == null || !sfsCache.hasSensitiveFieldsOn(model)) return filters;
        FieldPlan plan = fieldPlan(pi, model, readAccess(access));
        if (plan.isEmpty() || !mentionsAny(filters, plan)) return filters;
        return guardNode(model, filters, plan);
    }

    private static boolean mentionsAny(Filters node, FieldPlan plan) {
        if (node == null) return false;
        if (node.getType() == FilterType.LEAF) {
            FilterUnit unit = node.getFilterUnit();
            String field = unit == null ? null : unit.getField();
            return field != null && (plan.blocked().contains(field) || plan.conditional().contains(field));
        }
        if (node.getType() == FilterType.TREE && node.getChildren() != null) {
            for (Filters child : node.getChildren()) {
                if (mentionsAny(child, plan)) return true;
            }
        }
        return false;
    }

    /** Rebuilt rather than mutated: the caller's filters may be shared. */
    private Filters guardNode(String model, Filters node, FieldPlan plan) {
        if (node.getType() == FilterType.LEAF) {
            FilterUnit unit = node.getFilterUnit();
            String field = unit == null ? null : unit.getField();
            if (field == null) return node;
            if (plan.blocked().contains(field)) return ScopeRuleCompiler.matchNone();
            if (!plan.conditional().contains(field)) return node;
            List<Grant> granting = new ArrayList<>();
            plan.readersByFields().forEach((fields, readers) -> {
                if (fields.contains(field)) granting.addAll(readers);
            });
            Filters where = anyOf(granting.stream().map(g -> grantScope(g, model)).toList());
            return where == null ? node : Filters.and(node, where);
        }
        if (node.getType() != FilterType.TREE || node.getChildren() == null) return node;
        List<Filters> guarded = new ArrayList<>(node.getChildren().size());
        for (Filters child : node.getChildren()) {
            guarded.add(guardNode(model, child, plan));
        }
        Filters copy = new Filters();
        copy.setType(FilterType.TREE);
        copy.setLogicOperator(node.getLogicOperator());
        copy.setChildren(guarded);
        return copy;
    }

    // ─────────────────────── grant pairs ───────────────────────

    /**
     * The rows {@code access} reaches on {@code model}: the union, over the roles holding that action,
     * of each role's own rows. {@code null} means unrestricted.
     *
     * <p>Each role is a grant pair, and its rows answer only for its own actions. A role that may view
     * everyone and a role that may edit one department add up to viewing everyone and editing that
     * department — not, as merging the rules of both used to make it, editing everyone.
     */
    Filters rowScope(PermissionInfo pi, String model, AccessType access) {
        return anyOf(holders(pi, model, access).stream().map(g -> grantScope(g, model)).toList());
    }

    /**
     * The grants that decide {@code access} on {@code model}: the roles holding that action.
     *
     * <p>Every role when the snapshot predates per-role grants (the unions, read as the one combined
     * role they were written as), when the action is registered to no endpoint permission at all, or
     * when none of the caller's roles holds it. The last two are the same case seen from two sides:
     * nothing names who may perform the action, so whatever reached this read was authorized
     * elsewhere — a flow, a service acting for the caller — and row scope stays what it was before
     * actions had scopes of their own. Where a role does hold the action, only the holders count.
     */
    List<Grant> holders(PermissionInfo pi, String model, AccessType access) {
        List<RoleGrant> roles = pi == null ? null : pi.getRoleGrants();
        if (roles == null) {
            return List.of(Grant.union(pi));
        }
        List<Grant> all = roles.stream().map(Grant::of).toList();
        Set<String> candidates = actionPermissions(model, access);
        if (candidates.isEmpty()) {
            return all;
        }
        List<Grant> holding = all.stream()
                .filter(g -> g.permissions().stream().anyMatch(candidates::contains))
                .toList();
        return holding.isEmpty() ? all : holding;
    }

    /**
     * The permission ids that grant {@code access} on {@code model}; empty when none is registered.
     *
     * <p>Asked of the endpoint index by a URL the action always derives, the same way
     * {@link #hasModelActionGrant} asks. An export has no such URL — its endpoints are shared by every
     * model, the model riding in a parameter — so it is asked by model and action instead.
     */
    private Set<String> actionPermissions(String model, AccessType access) {
        EndpointIndex index = endpointIndexSupplier.get();
        if (index == null || model == null || access == null) {
            return Set.of();
        }
        if (access == AccessType.EXPORT) {
            return index.actionPermissions(model, "export");
        }
        String uri = CANONICAL_ACTION_URI.get(access);
        return uri == null ? Set.of() : index.lookup("/" + model + uri, "POST");
    }

    /**
     * One grant's rows on {@code model}: its company grant AND its row rules — or, where it holds no
     * rule for the model, what the model resolves to without one. {@code null} means unrestricted.
     *
     * <p>The company grant bounds every multi-company model, on its own axis: which legal entities a
     * role may reach is a property of the role, so it does not ride the per-model rules and is not
     * waived by an ALL rule on some model. Admins never get here — a tenant admin sees every company
     * in its tenant.
     */
    private Filters grantScope(Grant g, String model) {
        Filters company = companyScope(model, g.companies());
        Filters rows;
        if (hasExplicitRules(g, model)) {
            rows = scopeCompiler.compile(withDeclaredScope(g.rules(model), model), model);
            rows = allOf(rows, conditionOf(g, model));
        } else {
            rows = scopeWithoutGrant(model, g);
        }
        return allOf(company, rows);
    }

    /**
     * The grant's condition on {@code model}, compiled as a CUSTOM rule is — the same Filters JSON,
     * the same placeholder guard and subtree rewrite — so an unreadable condition fails closed the way
     * an unreadable rule does. {@code null} when there is none.
     */
    private Filters conditionOf(Grant g, String model) {
        JsonNode condition = g.conditions().get(model);
        if (condition == null || !condition.isArray() || condition.isEmpty()) {
            return null;
        }
        ScopeRule rule = new ScopeRule();
        rule.setScopeType(ScopeType.CUSTOM);
        rule.setScopeExpr(condition);
        return scopeCompiler.compile(List.of(rule), model);
    }

    /** AND of two scopes where {@code null} is "no restriction". */
    private static Filters allOf(Filters a, Filters b) {
        if (a == null) return b;
        if (b == null) return a;
        return Filters.and(a, b);
    }

    /** OR of the scopes; {@code null} (unrestricted) as soon as one is, match-none when there are none. */
    private static Filters anyOf(List<Filters> scopes) {
        if (scopes.isEmpty()) return ScopeRuleCompiler.matchNone();
        List<Filters> parts = new ArrayList<>(scopes.size());
        for (Filters scope : scopes) {
            if (scope == null) return null;
            parts.add(scope);
        }
        if (parts.size() == 1) return parts.getFirst();
        Filters or = Filters.or();
        or.setChildren(parts);
        return or;
    }

    /**
     * One role's grant, as the data plane reads it — or the snapshot's unions read as one role, for a
     * snapshot that predates per-role grants. Never null-valued: a missing part is an empty one.
     */
    record Grant(Set<String> permissions, Map<String, List<ScopeRule>> scopes,
                 Map<String, JsonNode> conditions, Map<String, Set<String>> sensitiveSets,
                 Set<Long> companies) {

        static Grant of(RoleGrant g) {
            return new Grant(orEmpty(g.getPermissions()), orEmpty(g.getModelScopeMap()),
                    orEmpty(g.getModelScopeConditions()), orEmpty(g.getModelSensitiveFieldSetsMap()),
                    g.getGrantedCompanyIds());
        }

        static Grant union(PermissionInfo pi) {
            if (pi == null) {
                return new Grant(Set.of(), Map.of(), Map.of(), Map.of(), null);
            }
            return new Grant(orEmpty(pi.getPermissions()), orEmpty(pi.getModelScopeMap()), Map.of(),
                    orEmpty(pi.getModelSensitiveFieldSetsMap()), pi.getGrantedCompanyIds());
        }

        List<ScopeRule> rules(String model) {
            return scopes.get(model);
        }

        private static <T> Set<T> orEmpty(Set<T> set) {
            return set == null ? Set.of() : set;
        }

        private static <K, V> Map<K, V> orEmpty(Map<K, V> map) {
            return map == null ? Map.of() : map;
        }
    }

    /** No-op when the rewriter is absent (older constructors, unit tests) or nothing matches. */
    /**
     * {@inheritDoc}
     *
     * <p>Kept as its own entry point so a read that crosses the caller's row range can still get the
     * rewrite: {@link #appendScopeAccessFilters} calls this first, and a relation expansion calls it
     * instead.
     */
    @Override
    public Filters rewriteScopeFilters(String model, Filters originalFilters) {
        return rewriteSubtrees(model, originalFilters);
    }

    private Filters rewriteSubtrees(String model, Filters filters) {
        SubtreeFilterRewriter rewriter = subtreeRewriterSupplier.get();
        return rewriter == null ? filters : rewriter.rewrite(model, filters);
    }

    /**
     * What a model resolves to when the caller holds no rule for it.
     *
     * <p>Separate from {@link #appendScopeAccessFilters} because the two answer different questions.
     * Above, the subject is the caller — may this principal bypass, which companies is it bounded to,
     * what did an administrator configure. Here the subject is the model — is it business data, a value
     * domain, or a child of something the caller can already see. Read as one method they hid that
     * boundary; the caller-side checks are now the whole of the public one and read as a policy.
     *
     * <p>Fail-closed is the default, and every branch below is an argument against it:
     * <ol>
     *   <li><b>Owned by a granted model</b> → follow that owner; see {@link #findReferencer} and
     *       {@link #followOwner}.</li>
     *   <li><b>Has a forward anchor</b> → real business data, which someone was supposed to grant. Closed.</li>
     *   <li><b>A country value domain</b> → the rows are a dropdown's own domain; see
     *       {@link #isCountryValueDomain}. Readable.</li>
     *   <li><b>A shared reference/config</b> → a ManyToOne target of a granted model. Readable.</li>
     *   <li><b>Otherwise</b> → nothing connects the caller to it. Closed.</li>
     * </ol>
     *
     * <p><b>The ownership edge is tested first, and the order is the whole point.</b> On a one-to-many
     * child the two tests read the SAME column: {@code EmpAttachment.employeeId} is both what makes
     * {@code SELF} applicable — so {@code hasForwardAnchor} answers true — and the back-reference
     * {@link #followOwner} would follow. Asking about the anchor first therefore rejected every
     * one-to-many child of a granted model as "business data nobody granted", and that is every such
     * child there is: a parent cannot hold N ids, so the FK lives on the child by necessity. The
     * follow-the-owner branch could only ever run for a one-to-one child, whose FK sits on the parent
     * and which consequently has no such column — which is why it looked correct. The only shape that
     * reached it was the only shape the fault could not affect.
     *
     * <p>{@code SHARED} deliberately stays behind the anchor test. Being referenced is not ownership,
     * and letting it jump the queue would open a business table merely because something points at it.
     * The two owned kinds are safe there precisely because they are bounded by the parent's own scope.
     *
     * <p>Cross-model display expansion never arrives here — it bypasses everything upstream via
     * {@code skipPermissionCheck}.
     */
    private Filters scopeWithoutGrant(String model, Grant g) {
        Referencer ref = findReferencer(model, g);
        if (ref != null && ref.kind() != Kind.SHARED) {
            return followOwner(ref, g);
        }
        if (hasForwardAnchor(model)) {
            return ScopeRuleCompiler.matchNone();
        }
        ScopeType declared = declaredScope(model);
        if (declared != null) {
            // ALL compiles to no filter at all, which is not the same answer as "no declaration" —
            // hence the null check on the TYPE above rather than on the compiled filter here.
            return scopeCompiler.compile(List.of(ruleOf(declared)), model);
        }
        if (isCountryValueDomain(model)) {
            return null;
        }
        // ref == null → nothing connects the caller to it; SHARED → shared reference/config, readable.
        return ref == null ? ScopeRuleCompiler.matchNone() : null;
    }

    /**
     * The scope this model declares for every caller — added to whatever rules the caller's role
     * holds, and standing alone when it holds none — or {@code null} when it declares none.
     *
     * <p>Read from {@link ModelDefaultScopeRegistry} — platform-level reference data, not a column
     * on the model's metadata. The scanner owns {@code SysModel} and diffs it back to the
     * annotations on every boot, so a value set there by hand would not survive; this lives in its
     * own table for that reason, and can therefore be changed without a release.
     */
    private ScopeType declaredScope(String model) {
        ModelDefaultScopeRegistry registry = defaultScopeSupplier.get();
        return registry == null ? null : registry.scopeFor(model);
    }

    private static final Set<ScopeType> UNIVERSAL_SCOPE_TYPES =
            EnumSet.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.CREATED_BY_SELF);

    /**
     * The role's own rules for {@code model}, plus the scope the model declares — OR-ed together by
     * the compiler like any two rules a role holds.
     *
     * <p>A declaration is a floor under every role, not a fallback for the roles that configured
     * nothing: someone whose role reaches some import histories still sees the ones they ran
     * themselves. The consequence is deliberate and worth stating — on a model declaring
     * {@code ALL}, the union is always {@code ALL}, so a rule configured on that model can widen
     * nothing and narrow nothing. Such a model has no row-level restriction to configure.
     *
     * <p>Skipped on a model with a scope anchor of its own, for the reason the no-grant path skips
     * it: declaring both means one of them is wrong, and the anchor is the safer reading.
     */
    private List<ScopeRule> withDeclaredScope(List<ScopeRule> rules, String model) {
        ScopeType declared = declaredScope(model);
        if (declared == null || hasForwardAnchor(model)) {
            return rules;
        }
        List<ScopeRule> merged = new ArrayList<>(rules);
        merged.add(ruleOf(declared));
        return merged;
    }

    /** A declared fallback as the compiler wants it — the same shape a configured rule arrives in. */
    private static ScopeRule ruleOf(ScopeType type) {
        ScopeRule rule = new ScopeRule();
        rule.setScopeType(type);
        return rule;
    }

    /**
     * Row-scope for a child the caller reaches through a model they were granted.
     *
     * <p>Both owned kinds re-enter scope for the parent, so the parent's own row-scope is applied
     * (parent strict ⇒ child strict) — nothing is widened, the child simply inherits the visibility
     * of the row that owns it.
     */
    private Filters followOwner(Referencer ref, Grant g) {
        if (ref.kind() == Kind.SHARED) {
            // Filtered out by the caller — an ownership edge is what reaches this method.
            return null;
        }
        // The owner's rows under the SAME grant: a child is visible through the role that can see its
        // owner, not through whichever of the caller's roles happens to.
        Filters ownerScope = grantScope(g, ref.parentModel());
        Filters owners = ownerScope == null ? new Filters() : ownerScope;
        return switch (ref.kind()) {
            case SHARED -> null;
            // ONE_TO_ONE owned child → the FK sits on the OWNER and holds the child's id,
            // so the visible child ids are the FK values of in-scope owner rows.
            case OWNED_ONE_TO_ONE -> {
                List<Serializable> visible = unscoped(() ->
                        modelService.getRelatedIds(ref.parentModel(), owners, ref.fkField()));
                yield visible.isEmpty()
                        ? ScopeRuleCompiler.matchNone()
                        : Filters.of(ModelConstant.ID, Operator.IN, visible);
            }
            // ONE_TO_MANY child → the FK sits on the CHILD and holds the parent's id, so we
            // constrain that back-reference column against the in-scope parent ids instead
            // of the child's own id.
            case CHILD_BY_BACKREF -> {
                List<?> parents = unscoped(() -> modelService.getIds(ref.parentModel(), owners));
                yield parents.isEmpty()
                        ? ScopeRuleCompiler.matchNone()
                        : Filters.of(ref.fkField(), Operator.IN, parents);
            }
        };
    }

    /**
     * Run a read with the caller's own row range switched off — for a read whose range this class has
     * already computed and passes in as a filter. Re-entering the scope chain would apply every role
     * of the caller on top of the one grant being asked about.
     */
    private static <T> T unscoped(Supplier<T> read) {
        Context ctx = ContextHolder.cloneContext();
        ctx.setSkipPermissionCheck(true);
        return ContextHolder.callWith(ctx, read::get);
    }

    // ─────────────────────── field mask ───────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Drops only the fields no reading role grants on any row. A field some reading role grants is
     * kept in the SELECT even where the caller may not see it on every row: which rows show it is a
     * per-row answer, given by {@link #maskResponseValue} once the rows are in hand.
     */
    @Override
    public Collection<String> filterReadableFields(String model, Collection<String> requested, AccessType accessType) {
        if (requested == null || requested.isEmpty() || shouldBypass()) return requested;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return requested;
        Set<String> blocked = fieldPlan(pi, model, readAccess(accessType)).blocked();
        if (blocked.isEmpty()) return requested;
        List<String> out = new ArrayList<>(requested.size());
        for (String f : requested) if (!blocked.contains(f)) out.add(f);
        return out;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Per row: a sensitive field shows on a row when a role that reads the row grants the field's
     * set. One role may see one department's salaries and another the whole staff list without them;
     * in one list, the salary column is filled on the first rows and empty on the rest. A row without its {@code id} cannot be placed in any role's rows, so it shows only the
     * fields every reading role grants.
     */
    @Override
    public <T> T maskResponseValue(String model, T value, AccessType accessType) {
        if (value == null || shouldBypass()) return value;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return value;
        FieldPlan plan = fieldPlan(pi, model, readAccess(accessType));
        if (plan.isEmpty()) return value;
        List<Map<String, Object>> rows = new ArrayList<>();
        collectRows(value, rows);
        maskRows(model, rows, plan);
        return value;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mask {@code rows} of {@code model} in place, as {@link #maskResponseValue} would — for rows
     * read some other way, such as the before / after values of a change log entry.
     */
    @Override
    public void maskRows(String model, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty() || shouldBypass()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return;
        FieldPlan plan = fieldPlan(pi, model, readAccess(AccessType.READ));
        if (!plan.isEmpty()) {
            maskRows(model, rows, plan);
        }
    }

    /** A read performed for an export masks by the exporting roles; any other read by the readers. */
    private static AccessType readAccess(AccessType requested) {
        AccessType bound = AccessScope.current();
        return bound == AccessType.EXPORT ? bound : (requested == null ? AccessType.READ : requested);
    }

    private static void collectRows(Object value, List<Map<String, Object>> out) {
        if (value == null) return;
        if (value instanceof Optional<?> opt) {
            opt.ifPresent(v -> collectRows(v, out));
            return;
        }
        if (value instanceof Page<?> page) {
            collectRows(page.getRows(), out);
            return;
        }
        if (value instanceof Collection<?> coll) {
            for (Object el : coll) collectRows(el, out);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) map;
            out.add(row);
        }
        // POJO / primitive → nothing to do here.
    }

    private void maskRows(String model, List<Map<String, Object>> rows, FieldPlan plan) {
        for (Map<String, Object> row : rows) {
            for (String f : plan.blocked()) {
                if (row.containsKey(f)) row.put(f, null);
            }
        }
        if (plan.conditional().isEmpty()) return;
        List<Serializable> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get(ModelConstant.ID) instanceof Serializable id && showsAny(row, plan.conditional())) {
                ids.add(id);
            }
        }
        // For each distinct set of conditional fields some readers grant, the rows those readers reach.
        Map<Set<String>, Set<Object>> reachedBy = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            plan.readersByFields().forEach((fields, readers) ->
                    reachedBy.put(fields, rowsReached(model, readers, ids)));
        }
        for (Map<String, Object> row : rows) {
            Object id = row.get(ModelConstant.ID);
            Set<String> visible = new java.util.HashSet<>();
            reachedBy.forEach((fields, reached) -> {
                if (id != null && reached.contains(normalizeId(id))) visible.addAll(fields);
            });
            for (String f : plan.conditional()) {
                if (!visible.contains(f) && row.containsKey(f)) row.put(f, null);
            }
        }
    }

    private static boolean showsAny(Map<String, Object> row, Set<String> fields) {
        for (String f : fields) {
            if (row.get(f) != null) return true;
        }
        return false;
    }

    /** Which of {@code ids} the grants reach on {@code model}, as normalized ids. */
    private Set<Object> rowsReached(String model, List<Grant> grants, List<Serializable> ids) {
        Filters scope = anyOf(grants.stream().map(g -> grantScope(g, model)).toList());
        if (scope == null) {
            return ids.stream().map(PermissionServiceImpl::normalizeId).collect(Collectors.toSet());
        }
        Filters target = Filters.and(Filters.of(ModelConstant.ID, Operator.IN, ids), scope);
        List<?> reached = unscoped(() -> modelService.getIds(model, target));
        return reached.stream().map(PermissionServiceImpl::normalizeId).collect(Collectors.toSet());
    }

    /** Ids compared as text: a row may carry a Long where the id query hands back an Integer. */
    private static Object normalizeId(Object id) {
        return id == null ? null : id.toString();
    }

    /**
     * How {@code model}'s sensitive fields split for the caller under {@code access}.
     *
     * <ul>
     *   <li><b>blocked</b> — no role holding the action grants the field: hidden on every row;</li>
     *   <li><b>conditional</b> — some do, some do not: shown on the rows of the roles that do;</li>
     *   <li>the rest — every holder grants it: shown wherever the row itself is.</li>
     * </ul>
     * A single role, or roles granting the same sets, leaves nothing conditional, and the mask is the
     * model-wide one it always was — no per-row query.
     */
    private FieldPlan fieldPlan(PermissionInfo pi, String model, AccessType access) {
        if (sfsCache == null || !sfsCache.hasSensitiveFieldsOn(model)) return FieldPlan.NONE;
        Set<String> sensitive = sfsCache.allSensitiveFieldsOn(model);
        List<Grant> holders = holders(pi, model, access);
        Set<String> anyGrants = new java.util.HashSet<>();
        Set<String> allGrant = null;
        Map<Grant, Set<String>> grantedBy = new java.util.LinkedHashMap<>();
        for (Grant g : holders) {
            Set<String> granted = sfsCache.grantedFieldsFor(model, g.sensitiveSets().getOrDefault(model, Set.of()));
            grantedBy.put(g, granted);
            anyGrants.addAll(granted);
            if (allGrant == null) {
                allGrant = new java.util.HashSet<>(granted);
            } else {
                allGrant.retainAll(granted);
            }
        }
        Set<String> blocked = new java.util.HashSet<>(sensitive);
        blocked.removeAll(anyGrants);
        Set<String> conditional = new java.util.HashSet<>(anyGrants);
        if (allGrant != null) conditional.removeAll(allGrant);
        conditional.retainAll(sensitive);
        Map<Set<String>, List<Grant>> readersByFields = new java.util.HashMap<>();
        if (!conditional.isEmpty()) {
            grantedBy.forEach((g, granted) -> {
                Set<String> fields = new java.util.HashSet<>(granted);
                fields.retainAll(conditional);
                if (!fields.isEmpty()) {
                    readersByFields.computeIfAbsent(fields, k -> new ArrayList<>()).add(g);
                }
            });
        }
        return new FieldPlan(blocked, conditional, readersByFields);
    }

    /**
     * @param blocked fields hidden on every row
     * @param conditional fields shown only on the rows of the roles granting them
     * @param readersByFields for each distinct set of conditional fields, the roles granting exactly it
     */
    private record FieldPlan(Set<String> blocked, Set<String> conditional,
                             Map<Set<String>, List<Grant>> readersByFields) {
        static final FieldPlan NONE = new FieldPlan(Set.of(), Set.of(), Map.of());

        boolean isEmpty() {
            return blocked.isEmpty() && conditional.isEmpty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>"Can't see on every row" is the field plan's blocked plus conditional: masked values sorted
     * among visible ones would place each hidden figure between its neighbours. The sort is dropped
     * rather than refused, as a list a user merely clicked a header on should still load; a grouping
     * or aggregate is refused, since dropping it would change what the result means.
     */
    @Override
    public void guardQuery(String model, FlexQuery flexQuery) {
        if (flexQuery == null || shouldBypass()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return;
        FieldPlan plan = fieldPlan(pi, model, readAccess(AccessType.READ));
        if (plan.isEmpty()) return;
        Set<String> masked = new java.util.HashSet<>(plan.blocked());
        masked.addAll(plan.conditional());
        Orders orders = flexQuery.getOrders();
        if (orders != null && orders.getFields().stream().anyMatch(masked::contains)) {
            Orders kept = new Orders();
            for (List<String> unit : orders.getOrderList()) {
                if (unit.isEmpty() || masked.contains(unit.getFirst())) continue;
                if (unit.size() > 1 && Orders.DESC.equalsIgnoreCase(unit.get(1))) {
                    kept.addDesc(unit.getFirst());
                } else {
                    kept.addAsc(unit.getFirst());
                }
            }
            flexQuery.setOrders(kept.isEmpty() ? null : kept);
        }
        List<String> grouped = new ArrayList<>();
        if (flexQuery.getGroupBy() != null) grouped.addAll(flexQuery.getGroupBy());
        if (flexQuery.getSplitBy() != null) grouped.addAll(flexQuery.getSplitBy());
        if (!AggFunctions.isEmpty(flexQuery.getAggFunctions())) {
            flexQuery.getAggFunctions().getFunctionList().forEach(f -> grouped.add(f.getField()));
        }
        for (String field : grouped) {
            if (field != null && masked.contains(field)) {
                throw new PermissionException("Records can't be grouped or summarised by "
                        + fieldLabel(model, field) + ": it is hidden on some of them.");
            }
        }
    }

    private static String fieldLabel(String model, String field) {
        return ModelManager.existField(model, field) ? ModelManager.getModelField(model, field).getLabel() : field;
    }

    // ─────────────────────── record-level access ───────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Judged by the same grant pairs the reads and writes are. A sensitive set is <b>hidden</b> on a
     * record no reading role that reaches it grants; <b>read-only</b> where some reading role grants it
     * but no editing role that reaches the record does. An action is available where the caller holds it
     * at all and a role holding it reaches the record. One query per role involved, whatever the number
     * of records.
     */
    @Override
    public List<RecordAccess> getRecordAccess(String model, Collection<? extends Serializable> ids) {
        List<Serializable> idList = ids == null ? List.of() : ids.stream().distinct().collect(Collectors.toList());
        Set<AccessType> held = EnumSet.noneOf(AccessType.class);
        for (AccessType action : List.of(AccessType.UPDATE, AccessType.DELETE)) {
            if (hasModelActionGrant(model, action)) held.add(action);
        }
        if (idList.isEmpty()) return List.of();
        if (shouldBypass() || PermissionInfo.hasFullDataAccess(currentPi())) {
            return idList.stream().map(id -> new RecordAccess(id, Set.of(), Set.of(), held)).toList();
        }
        PermissionInfo pi = currentPi();
        // The model's own sets, and those of its owned children shown on its form (attachedTo) — a
        // child row is reached through the record that owns it, so it follows that record's answer.
        Set<String> owned = new java.util.TreeSet<>();
        if (sfsCache != null) {
            owned.addAll(sfsCache.setIdsOwnedBy(model));
            owned.addAll(sfsCache.setIdsAttachedTo(model));
        }
        List<Grant> readers = holders(pi, model, AccessType.READ);
        List<Grant> editors = holders(pi, model, AccessType.UPDATE);
        List<Grant> deleters = holders(pi, model, AccessType.DELETE);
        Map<Grant, Set<Object>> reach = new java.util.HashMap<>();
        for (List<Grant> group : List.of(readers, editors, deleters)) {
            for (Grant g : group) {
                reach.computeIfAbsent(g, k -> rowsReached(model, List.of(k), idList));
            }
        }
        List<RecordAccess> out = new ArrayList<>(idList.size());
        for (Serializable id : idList) {
            Object key = normalizeId(id);
            List<Grant> reading = readers.stream().filter(g -> reach.get(g).contains(key)).toList();
            if (reading.isEmpty()) {
                // Not a record the caller can see at all: nothing of it shows and nothing can be done.
                out.add(new RecordAccess(id, owned, Set.of(), Set.of()));
                continue;
            }
            List<Grant> editing = held.contains(AccessType.UPDATE)
                    ? editors.stream().filter(g -> reach.get(g).contains(key)).toList()
                    : List.of();
            Set<String> hidden = new java.util.TreeSet<>();
            Set<String> readonly = new java.util.TreeSet<>();
            for (String set : owned) {
                if (reading.stream().noneMatch(g -> grantsSet(g, model, set))) {
                    hidden.add(set);
                } else if (editing.stream().noneMatch(g -> grantsSet(g, model, set))) {
                    readonly.add(set);
                }
            }
            Set<AccessType> actions = EnumSet.noneOf(AccessType.class);
            if (!editing.isEmpty()) actions.add(AccessType.UPDATE);
            if (held.contains(AccessType.DELETE) && deleters.stream().anyMatch(g -> reach.get(g).contains(key))) {
                actions.add(AccessType.DELETE);
            }
            out.add(new RecordAccess(id, hidden, readonly, actions));
        }
        return out;
    }

    /** {@inheritDoc} — a set is shown on a create form when some role that may create the model grants it. */
    @Override
    public CreateAccess getCreateAccess(String model) {
        if (shouldBypass() || sfsCache == null) return new CreateAccess(Set.of());
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return new CreateAccess(Set.of());
        Set<String> hidden = new java.util.TreeSet<>(sfsCache.setIdsOwnedBy(model));
        hidden.addAll(sfsCache.setIdsAttachedTo(model));
        List<Grant> creators = holders(pi, model, AccessType.CREATE);
        hidden.removeIf(set -> creators.stream().anyMatch(g -> grantsSet(g, model, set)));
        return new CreateAccess(hidden);
    }

    /** Whether the grant holds {@code set}, wherever the set's own model is — {@code model} when unknown. */
    private boolean grantsSet(Grant g, String model, String set) {
        String setModel = sfsCache == null ? null : sfsCache.modelOf(set);
        return g.sensitiveSets().getOrDefault(setModel != null ? setModel : model, Set.of()).contains(set);
    }

    // ─────────────────────── write guard ───────────────────────

    @Override
    public void checkModelFieldsAccess(String model, Collection<String> fields, AccessType accessType) {
        if (fields == null || fields.isEmpty() || shouldBypass()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return;
        Set<String> blocked = fieldPlan(pi, model, readAccess(accessType)).blocked();
        if (blocked.isEmpty()) return;
        for (String f : fields) {
            if (blocked.contains(f)) {
                throw new PermissionException(
                        "No " + accessType + " permission for field " + model + "." + f);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>On a write, a sensitive field may only be written on the rows of a role that holds the write
     * action AND grants the field's set — both in the same role. A role that may edit a department and
     * a role that may see salaries do not add up to editing the department's salaries.
     */
    @Override
    public void checkIdsFieldsAccess(String model,
                                     Collection<? extends Serializable> ids,
                                     Set<String> fields,
                                     AccessType accessType) {
        if (AccessType.CREATE.equals(accessType) || AccessType.UPDATE.equals(accessType)) {
            // The rows first: an id the action cannot reach at all is refused for that, not for
            // whichever sensitive field the payload happens to carry.
            checkIdsAccess(model, ids, accessType);
            checkSensitiveWrite(model, ids, fields, accessType);
        } else {
            checkModelFieldsAccess(model, fields, accessType);
            checkIdsAccess(model, ids, accessType);
        }
    }

    private void checkSensitiveWrite(String model, Collection<? extends Serializable> ids, Set<String> fields,
                                     AccessType accessType) {
        if (fields == null || fields.isEmpty() || shouldBypass()) return;
        if (sfsCache == null || !sfsCache.hasSensitiveFieldsOn(model)) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return;
        Set<String> sensitive = sfsCache.allSensitiveFieldsOn(model);
        List<Grant> writers = holders(pi, model, accessType);
        // Fields written by the same roles are checked together: one count per distinct set of roles.
        Map<List<Grant>, List<String>> fieldsByWriters = new java.util.LinkedHashMap<>();
        for (String f : fields) {
            if (!sensitive.contains(f)) continue;
            List<Grant> grantingWriters = writers.stream()
                    .filter(g -> sfsCache.grantedFieldsFor(model, g.sensitiveSets().getOrDefault(model, Set.of()))
                            .contains(f))
                    .toList();
            if (grantingWriters.isEmpty()) {
                throw sensitiveWriteDenied(model, f);
            }
            fieldsByWriters.computeIfAbsent(grantingWriters, k -> new ArrayList<>()).add(f);
        }
        if (fieldsByWriters.isEmpty() || ids == null || ids.isEmpty()) return;
        List<Serializable> idList = ids.stream().distinct().collect(Collectors.toList());
        fieldsByWriters.forEach((grantingWriters, written) -> {
            Filters scope = anyOf(grantingWriters.stream().map(g -> grantScope(g, model)).toList());
            if (scope == null) return;
            Filters target = Filters.and(Filters.of(ModelConstant.ID, Operator.IN, idList), scope);
            long reached = unscoped(() -> modelService.count(model, target));
            if (reached != idList.size()) {
                throw sensitiveWriteDenied(model, written.getFirst());
            }
        });
    }

    /**
     * Named the way a user sees them: the set's short label ("IPA") and the model ("employee") —
     * "You don't have permission to edit IPA fields for this employee." A file import reads "update",
     * since the uploader is not editing anything.
     */
    private PermissionException sensitiveWriteDenied(String model, String field) {
        String set = sfsCache.setIdsContaining(model, field).stream()
                .map(sfsCache::labelOf)
                .filter(StringUtils::isNotBlank)
                .sorted()
                .findFirst()
                .orElse(field);
        String label = ModelManager.existModel(model) && StringUtils.isNotBlank(ModelManager.getModel(model).getLabel())
                ? ModelManager.getModel(model).getLabel().toLowerCase()
                : model;
        String verb = ImportScope.isActive() ? "update" : "edit";
        return new PermissionException("You don't have permission to " + verb + " " + set + " fields for this " + label + ".");
    }

    @Override
    public void checkWritePayload(String model, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty() || shouldBypass()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return;
        // Before the write, the rows are not known: refuse only what no role grants at all. Which rows
        // a granted field may be written on is checked once the ids are (checkIdsFieldsAccess).
        Set<String> blocked = blockedForAnyRole(pi, model);
        if (blocked.isEmpty()) return;
        for (String f : payload.keySet()) {
            if (blocked.contains(f)) {
                throw sensitiveWriteDenied(model, f);
            }
        }
    }

    /** Sensitive fields on {@code model} that none of the caller's roles grants. */
    private Set<String> blockedForAnyRole(PermissionInfo pi, String model) {
        if (sfsCache == null || !sfsCache.hasSensitiveFieldsOn(model)) return Set.of();
        Set<String> granted = new java.util.HashSet<>();
        List<RoleGrant> roles = pi == null ? null : pi.getRoleGrants();
        List<Grant> grants = roles == null ? List.of(Grant.union(pi)) : roles.stream().map(Grant::of).toList();
        for (Grant g : grants) {
            granted.addAll(g.sensitiveSets().getOrDefault(model, Set.of()));
        }
        return sfsCache.computeForbiddenFields(model, granted);
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
     * Enforce that every id is within the rows {@code accessType} reaches.
     *
     * <p>Counts the ids back with the action's row range AND-ed on — computed here from the roles
     * holding the action, and run with the caller's own range switched off so the other roles are not
     * applied on top. When the count differs from the caller's id list size, at least one id is either
     * outside the range OR non-existent; both cases are rejected without distinguishing (to avoid an
     * info-leak channel).
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
        if (ids == null || ids.isEmpty() || shouldBypass()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return;
        // De-duplicated at the entry, because the comparison below is against a COUNT and SQL's IN
        // de-duplicates: deleteByIds(model, [7, 7]) counted 1 against a size of 2 and threw on a
        // legitimate call. RequirePermissionAspect already worked around this by de-duplicating
        // before the call; doing it here is what makes that workaround unnecessary, and it keeps
        // one yardstick for both comparisons — the owned branch below used a distinct() count while
        // the generic one used the raw size.
        List<Serializable> idList = ids.stream().distinct().collect(Collectors.toList());

        // The ids must all lie in the rows the action reaches: the union of what each role holding it
        // can reach, so a role that may only view never lends its rows to an update.
        Filters scope = anyOf(holders(pi, model, accessType).stream()
                .map(g -> idCheckScope(g, model, accessType))
                .toList());
        if (scope == null) {
            return;
        }
        Filters target = Filters.and(Filters.of(ModelConstant.ID, Operator.IN, idList), scope);
        long visible = unscoped(() -> modelService.count(model, target));
        if (visible != idList.size()) {
            throw outOfScope(model, accessType);
        }
    }

    /**
     * Which of {@code model}'s rows one grant lets {@code accessType} name by id; {@code null} for all.
     *
     * <p>With rules for the model it is the grant's ordinary row range. Without, the model is checked
     * by how the grant reaches it rather than fail-closed to nothing:
     * <ul>
     *   <li><b>owned one-to-one</b> — the FK is on the owner, so the ids are those an in-range owner
     *       points at;</li>
     *   <li><b>shared reference / config</b> without an anchor of its own — readable, nothing to check;</li>
     *   <li><b>reached by nothing</b> and without an anchor — bookkeeping the runtime writes as a side
     *       effect of work it already authorized: a file record, an import or export history row, a login
     *       entry, a cron log. On CREATE the ids were minted by this very call, so there is no
     *       pre-existing row to expose and no rule could ever put them "in scope" — the check would be a
     *       wall rather than a control. What authorized the write is the action that caused it, checked
     *       where it happened. Reading, updating and deleting such a row by id follow the scope the model
     *       declares for every caller (its own rows, for a history), and fail closed without one;</li>
     *   <li>anything else — a one-to-many child, or a model with an anchor nobody granted — is its
     *       ordinary row range, which already resolves to "the back-reference lands on an in-range
     *       parent" or to nothing.</li>
     * </ul>
     * Ownership edges are resolved ahead of the anchor test, mirroring {@link #scopeWithoutGrant} —
     * see its javadoc for why the order decides whether one-to-many children work at all.
     */
    private Filters idCheckScope(Grant g, String model, AccessType accessType) {
        if (hasExplicitRules(g, model)) {
            return grantScope(g, model);
        }
        Referencer ref = findReferencer(model, g);
        Kind kind = ref == null ? null : ref.kind();
        if (kind == Kind.OWNED_ONE_TO_ONE) {
            return followOwner(ref, g);
        }
        if (kind == Kind.SHARED && !hasForwardAnchor(model)) {
            return null;
        }
        if (kind == null && !hasForwardAnchor(model)) {
            if (AccessType.CREATE.equals(accessType)) {
                return null;
            }
            // A model declaring a default scope says who its rows belong to — an import history row to
            // whoever ran the import. Its own rows can then be named by id: the import that ran under
            // the caller has to write its outcome back, and refusing that left the run "Processing".
            ScopeType declared = declaredScope(model);
            return declared == null
                    ? ScopeRuleCompiler.matchNone()
                    : scopeCompiler.compile(List.of(ruleOf(declared)), model);
        }
        return grantScope(g, model);
    }

    /**
     * The refusal for ids outside the action's rows. A new record outside the creator's scope gets the
     * sentence a user can act on; the others keep naming the model, for the logs they mostly end up in.
     * Out of range and non-existent are not told apart — that would be an information leak.
     */
    private static PermissionException outOfScope(String model, AccessType accessType) {
        if (AccessType.CREATE.equals(accessType)) {
            return new PermissionException("The record is outside your data scope.");
        }
        return new PermissionException("Some " + model + " ids are outside your " + accessType + " scope");
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
        if (PermissionInfo.hasFullDataAccess(pi)) return Set.of();
        return fieldPlan(pi, model, readAccess(accessType)).blocked();
    }

    // ─────────────────────── helpers ───────────────────────

    private static boolean shouldBypass() {
        if (!ContextHolder.existContext()) return true;
        Context ctx = ContextHolder.getContext();
        return ctx.isSkipPermissionCheck() || ctx.getUserId() == null;
    }

    private PermissionInfo currentPi() {
        Context ctx = ContextHolder.getContext();
        return snapshotProvider.get(ctx.getTenantId(), ctx.getUserId());
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
     * <p>No read that names rows by {@code id} is exempt. Display expansion — the one legitimate
     * by-id read of rows outside the grant, so that a referenced row's label is not blanked — never
     * reaches this method: {@code DataPipelineProxy.processReadData} carries
     * {@code @SkipPermissionCheck}, so {@code shouldBypass()} returns before the grant is consulted.
     * Everything else that happens to name an id must stay bounded. A caller could otherwise add
     * {@code ["id", "&gt;", 0]} to a {@code searchList} body and read an ungranted company whole, and
     * {@code checkIdsAccess} — the gate between a caller and {@code deleteByIds} / {@code updateList}
     * — verifies its targets by counting them back with a filter that names {@code id}, so any holder
     * of the model's delete permission could remove a row of a company they were never granted.
     *
     * <p>So the grant applies to every read, id-named or not. The cost is that a by-id read of a row
     * outside the grant comes back empty instead of returning the row. That is what a grant means.
     */
    // Package-private for test: the empty-grant default and the path-anchored case both fail silently.
    Filters appendCompanyGrant(String model, Filters filters, PermissionInfo pi) {
        Filters scope = companyScope(model, pi == null ? null : pi.getGrantedCompanyIds());
        return scope == null ? filters : combineAnd(filters, scope);
    }

    /**
     * The company grant on {@code model} as a filter of its own, {@code null} when it does not narrow —
     * see {@link #appendCompanyGrant}, which is this AND-ed onto a caller's filters.
     */
    private Filters companyScope(String model, Set<Long> granted) {
        if (granted == null) {
            return null;   // no company axis configured → unrestricted
        }
        if (model == null || !ModelManager.existModel(model)) {
            return null;
        }
        // The company model itself is bounded by its own id. It is deliberately NOT multiCompany
        // (self-scoping is rejected at boot: it has no company reference to anchor on), so without
        // this branch the grant would never reach the company list — every company picker and every
        // "my companies" read would offer legal entities the role cannot reach.
        String companyField = ModelConstant.COMPANY_MODEL.equals(model)
                ? ModelConstant.ID
                : companyAnchorOf(model);
        if (companyField == null) {
            return null;
        }
        if (granted.isEmpty()) {
            // Configured to reach no company — distinct from unconfigured, handled above. Matching
            // nothing is the point: a role written this way (a self-service employee role) must not
            // see a multi-company row, and its own row scope is what still lets it see itself.
            return ScopeRuleCompiler.matchNone();
        }
        // Sorted so the same grant renders the same SQL every time — set iteration order would vary
        // the statement text between requests and defeat statement caching.
        List<Serializable> ids = new ArrayList<>(granted);
        ids.sort(null);
        return Filters.of(companyField, Operator.IN, ids);
    }

    /**
     * The anchor a model reaches its company through, or null when it has none.
     *
     * <p>The field name is fixed ({@link ModelConstant#COMPANY_FIELD}) and asserted at init, so this
     * only has to answer whether the model is on the company axis at all.
     */
    private String companyAnchorOf(String model) {
        return ModelManager.getModel(model).isMultiCompany() ? ModelConstant.COMPANY_FIELD : null;
    }


    private static boolean hasExplicitRules(Grant g, String model) {
        List<ScopeRule> r = g.rules(model);
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


    /**
     * A country value domain — a table whose rows are one country's allowed values for some field — is
     * readable without a grant.
     *
     * <p>Row-scoping one is meaningless: the rows are the domain of a dropdown, so anyone who can open the
     * form needs all of them, and there is nothing in "Singapore issues NRIC, FIN and Passport" to protect.
     * What narrows them is the country axis, which is data correctness rather than authorization, and what
     * protects them is the endpoint permission on the page that maintains them — writes are untouched here.
     *
     * <p>These are the tables that used to be {@code @OptionSet} enums and only became tables because their
     * values differ per country. The enum side never had this problem — {@code /SysOptionSet/getOptionItems/*}
     * is yml-whitelisted as tenant-public dictionary metadata and served from {@code OptionManager}'s memory,
     * so it is not a scoped read at all. This restores the treatment they lost on the way out of the enum.
     *
     * <p>Without it they fail closed, and invisibly: {@code IdType} is only ever referenced from
     * {@code EmployeeProfile}, which is reached through {@code Employee} rather than granted in its own
     * right, so {@link #findReferencer} — which scans the GRANTED models — finds nothing and the read
     * returns zero rows. On screen that is an ID Type dropdown reading "No options available", with the
     * data present and the country filter correct.
     *
     * <h3>Why these two flags</h3>
     * {@code multiCountry} is a declaration about what the data IS — "rows are partitioned by country" —
     * rather than about how it is keyed, which makes it the honest thing for an authorization rule to read.
     * It does not on its own mean "value domain": a country-partitioned business table would carry it too.
     * {@code !multiTenant} is what rules that out — business data belongs to a tenant, a value domain does
     * not — and the two together select, on both live databases, exactly seven models: {@code IdType},
     * {@code PassType}, {@code ResidenceStatus}, {@code EmploymentType}, {@code HighestEducationLevel},
     * {@code HighestEducationTrack}, {@code WorkPattern}. No platform metadata, no {@code Navigation},
     * no {@code Plan} — nothing but the catalogues the country axis exists for.
     *
     * <p>A broader predicate was measured and rejected. Keying on {@code EXTERNAL_ID} (code-as-id) instead
     * selects 24 models — the seven plus every {@code Sys*} catalogue, {@code Navigation}, {@code Permission},
     * {@code Plan}, the flow templates. Those are all definitions rather than data, and their sensitive
     * counterparts stay closed ({@code RoleNavigation}, {@code TenantSubscription}, {@code FlowInstance} are
     * all surrogate-keyed), so it was defensible — but it opens thirteen models this bug never needed, and
     * a rule that reads a key strategy to infer a purpose is inference where a declaration was available.
     *
     * <h3>What it deliberately leaves broken</h3>
     * {@code CountryRegion} (34 referring fields), {@code Currency}, {@code CountrySubdivision} and
     * {@code Bank} are value domains too, and still need an explicit grant. The first three are not
     * {@code multiCountry} and must not be made so — they ARE the country and currency masters, not data
     * partitioned by country, and marking them would be a lie told to a permission rule. {@code Bank}
     * genuinely should be {@code multiCountry} (its rows are per-country: the clearing code is national and
     * the BIC embeds the country) and is pending that change. Until then their absence from a role's scope
     * is what it has always been — a configuration gap, now the only one left in this class.
     *
     * <p>Reached only after {@link #hasForwardAnchor}, so a model carrying an employee / department /
     * company anchor can never qualify however it is flagged — the anchor list stays in one place
     * (the {@code DataScopeType} registry) instead of being restated here.
     */
    private boolean isCountryValueDomain(String model) {
        MetaModel meta = ModelManager.getModel(model);
        return meta != null
                && meta.isMultiCountry()
                && !meta.isMultiTenant();
    }

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
     *   <li>failing all three, a {@code MANY_TO_ONE} referrer on an <b>owned child</b> of a granted
     *       model → also shared ({@link #sharedThroughOwnedChild});</li>
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
    private Referencer findReferencer(String childModel, Grant g) {
        Referencer backRef = null;
        Referencer shared = null;
        List<String> ownedChildren = new ArrayList<>();
        for (String granted : g.scopes().keySet()) {
            if (!ModelManager.existModel(granted)) continue;
            for (MetaField f : ModelManager.getModelFields(granted)) {
                String related = f.getRelatedModel();
                if (related == null) continue;
                boolean owned = f.getFieldType() == FieldType.ONE_TO_ONE
                        || f.getFieldType() == FieldType.ONE_TO_MANY;
                if (!childModel.equals(related)) {
                    if (owned) ownedChildren.add(related);
                    continue;
                }
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
        if (backRef != null) return backRef;
        if (shared != null) return shared;
        return sharedThroughOwnedChild(childModel, ownedChildren);
    }

    /**
     * Second hop, shared references only: a config master referenced from an <b>owned child</b> of a
     * granted model.
     *
     * <p>An owned child is never granted in its own right — the role wizard does not offer OneToOne /
     * OneToMany children as separate models, precisely because they are bounded by the parent's scope —
     * so a master reached only through one is invisible to the single-hop scan above and fails closed.
     * On screen that is a required dropdown reading "No options available" with the data present: a
     * preboarding form reaches Attendance Group / Holiday Calendar / Leave Policy through
     * {@code EmpPreOnboarding → EmpPreTimeProfile → …}, two edges away from the only granted model.
     *
     * <p>This restores the symmetry the endpoint layer already assumes. {@code EndpointIndex} derives
     * lookup grants two layers deep — direct relations get the full view set, relations of relations get
     * the picker subset — so the caller is allowed to CALL {@code /AttendanceGroup/searchName} and then
     * row-scope hands it zero rows. One layer has to move; widening the scope scan is the cheaper of the
     * two, because narrowing the endpoint derivation would break the nested sub-form pickers it was
     * written for.
     *
     * <p>Only {@code MANY_TO_ONE} qualifies, and only after both single-hop kinds miss. An ownership edge
     * is a statement about the granted model's own rows and stays a one-hop question; a shared master
     * is not owned by anyone, so the extra hop cannot widen anything the direct case would not already.
     */
    private Referencer sharedThroughOwnedChild(String childModel, List<String> ownedChildren) {
        for (String owned : ownedChildren) {
            if (!ModelManager.existModel(owned)) continue;
            for (MetaField f : ModelManager.getModelFields(owned)) {
                if (!childModel.equals(f.getRelatedModel())) continue;
                if (f.getFieldType() == FieldType.MANY_TO_ONE) {
                    return new Referencer(owned, f.getFieldName(), Kind.SHARED);
                }
            }
        }
        return null;
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

    @Override
    public boolean hasModelActionGrant(String model, AccessType accessType) {
        if (shouldBypass() || model == null || accessType == null) return true;
        EndpointIndex endpointIndex = endpointIndexSupplier.get();
        if (endpointIndex == null) return true;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.isAdmin(pi)) return true;
        String uri = CANONICAL_ACTION_URI.get(accessType);
        if (uri == null) return true;
        Set<String> candidates = endpointIndex.lookup("/" + model + uri, "POST");
        // Unregistered means granted — see the interface javadoc for why this default is the opposite
        // of the interceptor's.
        if (candidates.isEmpty()) return true;
        Set<String> held = pi == null ? null : pi.getPermissions();
        if (held == null || held.isEmpty()) return false;
        for (String candidate : candidates) {
            if (held.contains(candidate)) return true;
        }
        return false;
    }

    /**
     * One endpoint per access type, chosen as the one {@code EndpointIndex} always derives for it — the
     * lookup only needs a URL that resolves to the same permission set, not every URL of that action.
     */
    private static final Map<AccessType, String> CANONICAL_ACTION_URI = Map.of(
            AccessType.CREATE, "/createOne",
            AccessType.UPDATE, "/updateOne",
            AccessType.DELETE, "/deleteById",
            AccessType.READ, "/searchPage");
}
