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
import io.softa.framework.orm.enums.LogicOperator;
import io.softa.framework.orm.enums.OnDelete;
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
import io.softa.starter.permission.scope.IdPath;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
        originalFilters = expandConditionFields(model, originalFilters);
        originalFilters = guardSensitiveConditions(pi, model, access, originalFilters);
        originalFilters = boundRelatedConditions(pi, model, originalFilters);
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
     * match — {@code IS NULL} included, which is the conservative reading of "unknown".
     *
     * <p>A path through to-one relations into another model's sensitive field — {@code
     * employeeId.salary} on a leave request — is judged by that model's plan, for the same action: no
     * granting role there and it matches nothing; granted on some of its rows and it becomes "the
     * relation points at a row where the condition holds and the caller may see the field", the ids
     * read up front with the caller's own range switched off (see {@link #guardPathLeaf}).
     */
    private Filters guardSensitiveConditions(PermissionInfo pi, String model, AccessType access, Filters filters) {
        if (filters == null || Filters.isEmpty(filters)) return filters;
        AccessType read = readAccess(access);
        Map<String, FieldPlan> plans = new java.util.HashMap<>();
        if (!mentionsMasked(pi, model, read, filters, plans)) return filters;
        return guardNode(pi, model, read, filters, plans);
    }

    private boolean mentionsMasked(PermissionInfo pi, String model, AccessType read, Filters node,
                                   Map<String, FieldPlan> plans) {
        if (node == null) return false;
        if (node.getType() == FilterType.LEAF) {
            FilterUnit unit = node.getFilterUnit();
            if (unit == null) return false;
            for (String field : unit.getEffectiveFields()) {
                if (maskedTarget(pi, model, field, read, plans) != null) return true;
            }
            return false;
        }
        if (node.getType() == FilterType.TREE && node.getChildren() != null) {
            for (Filters child : node.getChildren()) {
                if (mentionsMasked(pi, model, read, child, plans)) return true;
            }
        }
        return false;
    }

    /** Rebuilt rather than mutated: the caller's filters may be shared. */
    private Filters guardNode(PermissionInfo pi, String model, AccessType read, Filters node,
                              Map<String, FieldPlan> plans) {
        if (node.getType() == FilterType.LEAF) {
            FilterUnit unit = node.getFilterUnit();
            if (unit == null) return node;
            if (unit.isTuple()) {
                // A tuple compares several fields at once and cannot be narrowed field by field: one
                // masked field in it, and it matches nothing.
                for (String field : unit.getEffectiveFields()) {
                    if (maskedTarget(pi, model, field, read, plans) != null) return ScopeRuleCompiler.matchNone();
                }
                return node;
            }
            PathTarget target = maskedTarget(pi, model, unit.getField(), read, plans);
            if (target == null) return node;
            FieldPlan plan = plans.get(target.model());
            if (plan.blocked().contains(target.leaf())) return ScopeRuleCompiler.matchNone();
            Filters where = grantingScope(plan, target.model(), target.leaf());
            if (where == null) return node;
            return target.relationPath() == null
                    ? Filters.and(node, where)
                    : guardPathLeaf(unit, target, where);
        }
        if (node.getType() != FilterType.TREE || node.getChildren() == null) return node;
        List<Filters> guarded = new ArrayList<>(node.getChildren().size());
        for (Filters child : node.getChildren()) {
            guarded.add(guardNode(pi, model, read, child, plans));
        }
        Filters copy = new Filters();
        copy.setType(FilterType.TREE);
        copy.setLogicOperator(node.getLogicOperator());
        copy.setChildren(guarded);
        return copy;
    }

    /**
     * A condition on {@code relationPath.leaf}, rewritten as {@code relationPath IN} the target rows
     * where the condition holds and the granting roles reach.
     *
     * <p>Read up front as ids rather than AND-ed on as a cascaded condition: the granting roles' scope
     * is a filter on the target model, and prefixing every field in it with the path would both add
     * join depth past what the query builder accepts and misread any part of it that is not a plain
     * column of the target. The ids are at most the rows the caller may see the field on.
     */
    private Filters guardPathLeaf(FilterUnit unit, PathTarget target, Filters where) {
        Filters onTarget = Filters.and(Filters.of(target.leaf(), unit.getOperator(), unit.getValue()), where);
        List<?> ids = unscoped(() -> modelService.getIds(target.model(), onTarget));
        return ids.isEmpty()
                ? ScopeRuleCompiler.matchNone()
                : Filters.of(target.relationPath(), Operator.IN, ids);
    }

    /** The rows of the roles granting {@code field} on {@code model}; {@code null} when one of them reaches all. */
    private Filters grantingScope(FieldPlan plan, String model, String field) {
        List<Grant> granting = new ArrayList<>();
        plan.readersByFields().forEach((fields, readers) -> {
            if (fields.contains(field)) granting.addAll(readers);
        });
        return anyOf(granting.stream().map(g -> grantScope(g, model)).toList());
    }

    /**
     * Where {@code field} — the model's own, or a path through to-one relations — lands, when what it
     * lands on is a sensitive field the caller cannot see on every row (blocked or per-row for
     * {@code read}); {@code null} for anything else. The plan of the model it lands on is left in
     * {@code plans}.
     */
    private PathTarget maskedTarget(PermissionInfo pi, String model, String field, AccessType read,
                                    Map<String, FieldPlan> plans) {
        PathTarget target = resolvePath(model, field);
        if (target == null) return null;
        boolean sensitive = sfsCache != null && sfsCache.hasSensitiveFieldsOn(target.model())
                && sfsCache.allSensitiveFieldsOn(target.model()).contains(target.leaf());
        // A field another model's cascaded field reads is that model's to show: a condition on it, which
        // is what the cascade expands to, stays usable. Read directly it is still past the label.
        boolean pastLabel = mayBeLabelOnly(target.model(), read)
                && !labelFields(target.model()).contains(target.leaf())
                && !cascadeExposed().contains(target.model() + "." + target.leaf());
        if (!sensitive && !pastLabel) {
            return null;
        }
        FieldPlan plan = plans.computeIfAbsent(target.model(), m -> fieldPlan(pi, m, read));
        return plan.blocked().contains(target.leaf()) || plan.perRow().contains(target.leaf()) ? target : null;
    }

    /**
     * {@code field} resolved against {@code model}: a plain name is the model's own field; a dotted path
     * walks many-to-one / one-to-one relations to the model its last segment belongs to. {@code null}
     * when a segment is not such a relation — a path this guard cannot follow is left as it was.
     */
    private static PathTarget resolvePath(String model, String field) {
        if (field == null || model == null) return null;
        int dot = field.lastIndexOf('.');
        if (dot < 0) return new PathTarget(model, field, null);
        String current = model;
        for (String segment : field.substring(0, dot).split("\\.")) {
            if (!ModelManager.existField(current, segment)) return null;
            MetaField relation = ModelManager.getModelField(current, segment);
            if (relation == null || relation.getRelatedModel() == null
                    || (relation.getFieldType() != FieldType.MANY_TO_ONE
                        && relation.getFieldType() != FieldType.ONE_TO_ONE)) {
                return null;
            }
            current = relation.getRelatedModel();
        }
        return new PathTarget(current, field.substring(dot + 1), field.substring(0, dot));
    }

    /**
     * @param model        the model the leaf field belongs to
     * @param leaf         the field's own name on that model
     * @param relationPath the to-one path leading there; {@code null} for the queried model's own field
     */
    private record PathTarget(String model, String leaf, String relationPath) {}

    // ─────────────────────── conditions through relations ───────────────────────

    /**
     * The caller's conditions in the shape the query builder runs them: a cascaded field the model
     * declares ({@code deptName} for {@code deptId.name}) as the path it stands for, and {@code searchName}
     * as the fields it searches, OR-ed. The guards judge a condition by the field it lands on; an alias or
     * the virtual field would otherwise be judged by a name that lands on nothing, and pass. Rebuilt
     * rather than mutated: the caller's filters may be shared.
     */
    private static Filters expandConditionFields(String model, Filters node) {
        if (node == null) return null;
        if (node.getType() == FilterType.LEAF) {
            FilterUnit unit = node.getFilterUnit();
            if (unit == null || unit.isTuple() || unit.getField() == null) return node;
            String field = unit.getField();
            if (ModelConstant.SEARCH_NAME.equals(field)) {
                List<String> searched = ModelManager.existModel(model)
                        ? ModelManager.getModel(model).getSearchName() : List.of();
                if (searched == null || searched.isEmpty()) {
                    searched = List.of(ModelConstant.ID);
                }
                List<Filters> each = searched.stream()
                        .map(f -> Filters.of(expandAlias(model, f), unit.getOperator(), unit.getValue()))
                        .toList();
                return each.size() == 1 ? each.getFirst() : tree(LogicOperator.OR, each);
            }
            String expanded = expandAlias(model, field);
            return expanded.equals(field) ? node : Filters.of(expanded, unit.getOperator(), unit.getValue());
        }
        if (node.getType() != FilterType.TREE || node.getChildren() == null) return node;
        List<Filters> expanded = new ArrayList<>(node.getChildren().size());
        for (Filters child : node.getChildren()) {
            expanded.add(expandConditionFields(model, child));
        }
        return tree(node.getLogicOperator(), expanded);
    }

    /** {@code field} as the path it stands for when the model declares it as a cascaded field. */
    private static String expandAlias(String model, String field) {
        if (field == null || field.contains(".") || !ModelManager.existField(model, field)) return field;
        String cascaded = ModelManager.getModelField(model, field).getCascadedField();
        return StringUtils.isBlank(cascaded) ? field : cascaded;
    }

    private static Filters tree(LogicOperator operator, List<Filters> children) {
        Filters copy = new Filters();
        copy.setType(FilterType.TREE);
        copy.setLogicOperator(operator);
        copy.setChildren(children);
        return copy;
    }

    /**
     * A condition through a to-one relation into a model the caller reads under a row range, rewritten
     * as {@code relation IN} the related rows inside that range where the condition holds.
     *
     * <p>The query builder joins the related table with no range of its own, so {@code
     * managerId.dateOfBirth < 1980-01-01} on a readable row would answer a question about a related row
     * the caller cannot read — its value, found out one question at a time. The related rows are read
     * through the caller's own range on that model instead, which applies the same rewrite to whatever
     * the path crosses next. A related row outside the range never matches, {@code IS NOT SET}
     * included; a row with no related row still matches {@code IS NOT SET}, as it did.
     *
     * <p>A condition on what a reference already shows — the related row's id or display name — is left
     * as it is: the caller sees that label on the row whether or not they may read the related one.
     */
    private Filters boundRelatedConditions(PermissionInfo pi, String model, Filters node) {
        if (node == null) return null;
        if (node.getType() == FilterType.LEAF) {
            FilterUnit unit = node.getFilterUnit();
            if (unit == null) return node;
            if (unit.isTuple()) {
                // A tuple compares several fields at once and cannot be narrowed field by field.
                for (String field : unit.getEffectiveFields()) {
                    if (readsBeyondRange(pi, model, field)) return ScopeRuleCompiler.matchNone();
                }
                return node;
            }
            String field = unit.getField();
            if (!readsBeyondRange(pi, model, field)) return node;
            int dot = field.indexOf('.');
            String relation = field.substring(0, dot);
            String related = ModelManager.getModelField(model, relation).getRelatedModel();
            Filters onRelated = Filters.of(field.substring(dot + 1), unit.getOperator(), unit.getValue());
            // Read as a read of the related model, whatever action the outer read is performed for: an
            // export of the main model does not need the export of every model its conditions cross.
            List<?> ids = AccessScope.callAs(AccessType.READ, () -> modelService.getIds(related, onRelated));
            Filters within = ids.isEmpty()
                    ? ScopeRuleCompiler.matchNone()
                    : Filters.of(relation, Operator.IN, ids);
            return Operator.IS_NOT_SET.equals(unit.getOperator())
                    ? tree(LogicOperator.OR, new ArrayList<>(List.of(Filters.of(relation, Operator.IS_NOT_SET, null), within)))
                    : within;
        }
        if (node.getType() != FilterType.TREE || node.getChildren() == null) return node;
        List<Filters> bounded = new ArrayList<>(node.getChildren().size());
        for (Filters child : node.getChildren()) {
            bounded.add(boundRelatedConditions(pi, model, child));
        }
        return tree(node.getLogicOperator(), bounded);
    }

    /**
     * Whether {@code field}, a path through to-one relations from {@code model}, reads past the label of
     * a related row the caller reads under a row range — at the first relation or any one after it.
     */
    private boolean readsBeyondRange(PermissionInfo pi, String model, String field) {
        int dot = field == null ? -1 : field.indexOf('.');
        if (dot < 0 || !ModelManager.existField(model, field.substring(0, dot))) return false;
        MetaField relation = ModelManager.getModelField(model, field.substring(0, dot));
        if (relation.getRelatedModel() == null
                || (relation.getFieldType() != FieldType.MANY_TO_ONE
                    && relation.getFieldType() != FieldType.ONE_TO_ONE)) {
            return false;
        }
        String related = relation.getRelatedModel();
        String rest = expandAlias(related, field.substring(dot + 1));
        if (rowScope(pi, related, AccessType.READ) != null && !isLabel(related, rest)) return true;
        return readsBeyondRange(pi, related, rest);
    }

    /**
     * What a reference to a row of {@code model} shows of it: its id, its display name, and — on a tree
     * — its place in the tree, which a subtree filter on the reference ({@code deptId CHILD OF 12},
     * rewritten above onto {@code deptId.idPath}) walks.
     */
    private static boolean isLabel(String model, String field) {
        return ModelConstant.ID.equals(field) || IdPath.FIELD.equals(field)
                || ModelManager.getModelDisplayName(model).contains(field);
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
     *
     * <p>An export is the exception to the last case: when its permission is registered for the model
     * and none of the caller's roles holds it, there are no holders and the export reaches no row. The
     * export endpoints are shared by every model — the model rides in a parameter — so the endpoint gate
     * lets through anyone holding the export of <em>some</em> model, and cannot have authorized this one.
     * Falling back to every role there let a user who may export one model export or count every
     * readable row of any other. A model with no export permission registered at all keeps the fallback.
     */
    List<Grant> holders(PermissionInfo pi, String model, AccessType access) {
        List<Grant> all = grantsOf(pi);
        if (pi == null || pi.getRoleGrants() == null) {
            return all;
        }
        Set<String> candidates = actionPermissions(model, access);
        if (candidates.isEmpty()) {
            return all;
        }
        List<Grant> holding = all.stream()
                .filter(g -> g.permissions().stream().anyMatch(candidates::contains))
                .toList();
        if (holding.isEmpty()) {
            return access == AccessType.EXPORT ? List.of() : all;
        }
        return holding;
    }

    /**
     * The permission ids that grant {@code access} on {@code model}; empty when none is registered.
     *
     * <p>Asked of the endpoint index by model and action, over every standard endpoint of that action —
     * derived or hand-listed — the same way {@link #hasModelActionGrant} asks. An export is asked by the
     * action segment of the permission id instead: its endpoints are shared by every model, the model
     * riding in a parameter, so no endpoint path names the model.
     */
    private Set<String> actionPermissions(String model, AccessType access) {
        EndpointIndex index = endpointIndexSupplier.get();
        if (index == null || model == null || access == null) {
            return Set.of();
        }
        if (access == AccessType.EXPORT) {
            return index.actionPermissions(model, "export");
        }
        String action = MODEL_ACTIONS.get(access);
        return action == null ? Set.of() : index.modelActionPermissions(model, action);
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
        RequestMemo memo = RequestMemo.current();
        if (memo == null) {
            return computeGrantScope(g, model);
        }
        Optional<Filters> known = memo.scope(g, model);
        if (known == null) {
            Filters computed = computeGrantScope(g, model);
            memo.putScope(g, model, computed);
            return computed;
        }
        // A copy every time: the query builder rewrites the leaves of the filters it is handed in place.
        return known.map(Filters::deepCopy).orElse(null);
    }

    private Filters computeGrantScope(Grant g, String model) {
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
     * snapshot that predates per-role grants. A missing permission set, rule map, condition map or
     * sensitive-set map is read as an empty one. {@code companies} keeps the snapshot's tri-state:
     * {@code null} is no company restriction, empty reaches no company, otherwise exactly those.
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

    /**
     * {@inheritDoc}
     *
     * <p>The same steps as {@link #appendScopeAccessFilters} up to, and excluding, the row range — so a
     * label lookup such as {@code searchName} still crosses the range, but a condition on a sensitive
     * field matches only where the caller may see that field, exactly as on a direct read.
     */
    @Override
    public Filters appendScopeAccessFiltersIgnoringRowScope(String model, Filters originalFilters) {
        originalFilters = rewriteScopeFilters(model, originalFilters);
        if (shouldBypass()) return originalFilters;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return originalFilters;
        originalFilters = expandConditionFields(model, originalFilters);
        originalFilters = guardSensitiveConditions(pi, model, AccessScope.current(), originalFilters);
        return boundRelatedConditions(pi, model, originalFilters);
    }

    /**
     * {@inheritDoc}
     *
     * <p>True for whatever would bound the model's rows before {@link #scopeWithoutGrant} reaches for an
     * owner: a forward anchor, a declared scope, the company axis, or a rule some role of the caller holds
     * for it. What is left is a model reachable only through the row that owns it — exactly the shape
     * whose direct read either follows that owner or fails closed — and a relation expansion is already
     * standing on that owner's row.
     *
     * <p>An anchored child is not lost by answering true: read under the range, a one-to-many child of a
     * granted model still follows its owner ({@link #followOwner}), so the sub-rows of a row the caller
     * sees come back, and those of a row they do not, do not.
     *
     * <p>False where the range would add nothing — no bound context, a bypass, full data access — so the
     * expansion is not routed through a scope computation it does not need.
     */
    @Override
    public boolean hasOwnRowScope(String model) {
        if (shouldBypass()) return false;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return false;
        if (model == null || !ModelManager.existModel(model)) return true;
        if (hasForwardAnchor(model) || declaredScope(model) != null || companyAnchorOf(model) != null) {
            return true;
        }
        for (Grant g : grantsOf(pi)) {
            if (hasExplicitRules(g, model)) return true;
        }
        return false;
    }

    /**
     * The caller's grants, one per role — or the unions read as one role. The same instances for the
     * whole request, so what is remembered per grant ({@link RequestMemo}) is found again.
     */
    private static List<Grant> grantsOf(PermissionInfo pi) {
        RequestMemo memo = RequestMemo.current();
        if (memo == null || pi == null) {
            return buildGrants(pi);
        }
        return memo.grants(pi);
    }

    private static List<Grant> buildGrants(PermissionInfo pi) {
        List<RoleGrant> roles = pi == null ? null : pi.getRoleGrants();
        return roles == null ? List.of(Grant.union(pi)) : roles.stream().map(Grant::of).toList();
    }

    /**
     * What one request has already worked out: each caller's grants, field plans and each grant's rows.
     *
     * <p>One read asks the same questions many times — the query guard, the field filter, the condition
     * guard, the mask and each of its groups — and one write asks them again for the rows and for each
     * sensitive field. A grant's rows can cost queries of their own: a child model reached through its
     * owner reads the owner's ids. Answered once per request, they are not read again.
     *
     * <p>Held as a request attribute, so it is the request's and nothing else's: dropped with it, never
     * seen by another request, and absent off the request thread (an async task, a scheduled job), where
     * everything is simply computed. Plans are keyed by the {@link PermissionInfo} instance, which the
     * snapshot provider hands out once per tenant and user per request, so a switch of tenant or user
     * within a request finds its own entries. Grant rows depend on data, not only on the snapshot, so
     * every write check forgets them ({@link #forgetScopes}): the rows a write is judged by are read
     * after the writes before it.
     */
    static final class RequestMemo {

        private static final String ATTRIBUTE = PermissionServiceImpl.class.getName() + ".requestMemo";

        private final Map<PermissionInfo, List<Grant>> grants = new java.util.IdentityHashMap<>();
        private final Map<PermissionInfo, Map<String, FieldPlan>> plans = new java.util.IdentityHashMap<>();
        private final Map<Grant, Map<String, Optional<Filters>>> scopes = new java.util.IdentityHashMap<>();

        /** The current request's memo; {@code null} off a request. */
        static RequestMemo current() {
            org.springframework.web.context.request.RequestAttributes ra =
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (ra == null) {
                return null;
            }
            int scope = org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST;
            if (ra.getAttribute(ATTRIBUTE, scope) instanceof RequestMemo memo) {
                return memo;
            }
            RequestMemo memo = new RequestMemo();
            ra.setAttribute(ATTRIBUTE, memo, scope);
            return memo;
        }

        synchronized List<Grant> grants(PermissionInfo pi) {
            return grants.computeIfAbsent(pi, PermissionServiceImpl::buildGrants);
        }

        synchronized FieldPlan plan(PermissionInfo pi, String model, AccessType access) {
            Map<String, FieldPlan> byKey = plans.get(pi);
            return byKey == null ? null : byKey.get(model + '|' + access);
        }

        synchronized void putPlan(PermissionInfo pi, String model, AccessType access, FieldPlan plan) {
            plans.computeIfAbsent(pi, k -> new java.util.HashMap<>()).put(model + '|' + access, plan);
        }

        /** {@code null} when not yet known; an empty optional for "unrestricted". */
        synchronized Optional<Filters> scope(Grant g, String model) {
            Map<String, Optional<Filters>> byModel = scopes.get(g);
            return byModel == null ? null : byModel.get(model);
        }

        synchronized void putScope(Grant g, String model, Filters scope) {
            scopes.computeIfAbsent(g, k -> new java.util.HashMap<>())
                    .put(model, Optional.ofNullable(scope == null ? null : scope.deepCopy()));
        }

        synchronized void forgetScopes() {
            scopes.clear();
        }
    }

    /** Called by every write check before it reads anything — see {@link RequestMemo}. */
    private static void forgetScopes() {
        RequestMemo memo = RequestMemo.current();
        if (memo != null) {
            memo.forgetScopes();
        }
    }

    /** No-op when the rewriter is absent (older constructors, unit tests) or nothing matches. */
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
     *   <li><b>Owned by a granted model</b> → follow its owners; see {@link #findReferencers} and
     *       {@link #followOwners}.</li>
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
     * {@link #followOwners} would follow. Asking about the anchor first therefore rejected every
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
     * <p>A relation expansion arrives here only when it reads more than labels of a model with a row
     * range of its own (see {@link #hasOwnRowScope}); a label lookup crosses the range upstream, through
     * {@code ModelService.searchListIgnoringRowScope}.
     */
    private Filters scopeWithoutGrant(String model, Grant g) {
        List<Referencer> refs = findReferencers(model, g);
        Referencer ref = refs.isEmpty() ? null : refs.getFirst();
        if (ref != null && ref.kind() != Kind.SHARED) {
            return followOwners(refs, g);
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

    /**
     * Scope types that apply to <b>every</b> model, so their presence says nothing about
     * whether a model carries a scope anchor of its own: {@code ALL} / {@code CUSTOM} are
     * declared {@code appliesToAll} in the DataScopeType registry, and {@code CREATED_BY_SELF}
     * keys on {@code createdId} — a column {@code AuditableModel} puts on every table.
     *
     * <p>Counting them made {@link #hasForwardAnchor} true for every model, which made the
     * anchorless follow-the-owner fallback unreachable: every by-id read of a model with
     * no explicit grant fail-closed to zero rows / 403, even for a child row the caller reaches
     * through a parent it can see.
     */
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
     * Row-scope for a child the caller reaches through models they were granted.
     *
     * <p>Both owned kinds re-enter scope for the parent, so the parent's own row-scope is applied
     * (parent strict ⇒ child strict) — nothing is widened, the child simply inherits the visibility
     * of the rows that own it. With several owners a child row is in range when an in-range row of
     * any of them owns it: the union, which does not depend on the order the owners were found in.
     */
    private Filters followOwners(List<Referencer> refs, Grant g) {
        Set<Serializable> visibleIds = new LinkedHashSet<>();
        List<Filters> backRefFilters = new ArrayList<>();
        for (Referencer ref : refs) {
            // The owner's rows under the SAME grant: a child is visible through the role that can see
            // its owner, not through whichever of the caller's roles happens to.
            Filters ownerScope = grantScope(g, ref.parentModel());
            Filters owners = ownerScope == null ? new Filters() : ownerScope;
            switch (ref.kind()) {
                // Both callers hand over ownership edges only; a shared reference bounds nothing.
                case SHARED -> throw new IllegalArgumentException(
                        "A shared reference is not an ownership edge: " + ref);
                // ONE_TO_ONE owned child → the FK sits on the OWNER and holds the child's id,
                // so the visible child ids are the FK values of in-scope owner rows.
                case OWNED_ONE_TO_ONE -> visibleIds.addAll(unscoped(() ->
                        modelService.getRelatedIds(ref.parentModel(), owners, ref.fkField())));
                // ONE_TO_MANY child → the FK sits on the CHILD and holds the parent's id, so we
                // constrain that back-reference column against the in-scope parent ids instead
                // of the child's own id.
                case CHILD_BY_BACKREF -> {
                    List<?> parents = unscoped(() -> modelService.getIds(ref.parentModel(), owners));
                    if (!parents.isEmpty()) {
                        backRefFilters.add(Filters.of(ref.fkField(), Operator.IN, parents));
                    }
                }
            }
        }
        if (!visibleIds.isEmpty()) {
            backRefFilters.add(Filters.of(ModelConstant.ID, Operator.IN, new ArrayList<>(visibleIds)));
        }
        if (backRefFilters.isEmpty()) {
            return ScopeRuleCompiler.matchNone();
        }
        Filters union = backRefFilters.getFirst();
        for (int i = 1; i < backRefFilters.size(); i++) {
            union = Filters.or(union, backRefFilters.get(i));
        }
        return union;
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
        if (plan.perRow().isEmpty()) return;
        List<Serializable> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get(ModelConstant.ID) instanceof Serializable id && showsAny(row, plan.perRow())) {
                ids.add(id);
            }
        }
        // For each distinct set of per-row fields some readers grant, the rows those readers reach.
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
            for (String f : plan.perRow()) {
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
     *   <li><b>perRow</b> — some do, some do not: shown on the rows of the roles that do;</li>
     *   <li>the rest — every holder grants it: shown wherever the row itself is.</li>
     * </ul>
     * A single role, or roles granting the same sets, leaves nothing per-row, and the mask is the
     * model-wide one it always was — no per-row query.
     */
    private FieldPlan fieldPlan(PermissionInfo pi, String model, AccessType access) {
        boolean sensitive = sfsCache != null && sfsCache.hasSensitiveFieldsOn(model);
        if (!sensitive && !mayBeLabelOnly(model, access)) return FieldPlan.NONE;
        RequestMemo memo = RequestMemo.current();
        if (memo == null) {
            return computeFieldPlan(pi, model, access);
        }
        FieldPlan plan = memo.plan(pi, model, access);
        if (plan == null) {
            plan = computeFieldPlan(pi, model, access);
            memo.putPlan(pi, model, access, plan);
        }
        return plan;
    }

    private FieldPlan computeFieldPlan(PermissionInfo pi, String model, AccessType access) {
        Set<String> sensitive = sfsCache != null && sfsCache.hasSensitiveFieldsOn(model)
                ? sfsCache.allSensitiveFieldsOn(model) : Set.of();
        List<Grant> holders = holders(pi, model, access);
        Set<String> pastLabel = mayBeLabelOnly(model, access) ? pastLabelFields(model) : Set.of();
        Set<String> governed = new java.util.HashSet<>(sensitive);
        governed.addAll(pastLabel);
        if (governed.isEmpty()) return FieldPlan.NONE;
        // What each holding role shows of the governed fields: the sensitive fields its sets grant, and
        // the fields past the label when it was granted the model itself.
        Map<Grant, Set<String>> visibleBy = new java.util.LinkedHashMap<>();
        for (Grant g : holders) {
            Set<String> visible = new java.util.HashSet<>(governed);
            Set<String> granted = sfsCache == null || sensitive.isEmpty() ? Set.of()
                    : sfsCache.grantedFieldsFor(model, g.sensitiveSets().getOrDefault(model, Set.of()));
            for (String f : sensitive) {
                if (!granted.contains(f)) visible.remove(f);
            }
            if (!pastLabel.isEmpty() && !viewsInFull(g, model, 0)) {
                visible.removeAll(pastLabel);
            }
            visibleBy.put(g, visible);
        }
        Set<String> anyVisible = new java.util.HashSet<>();
        Set<String> allVisible = null;
        for (Set<String> visible : visibleBy.values()) {
            anyVisible.addAll(visible);
            if (allVisible == null) {
                allVisible = new java.util.HashSet<>(visible);
            } else {
                allVisible.retainAll(visible);
            }
        }
        Set<String> blocked = new java.util.HashSet<>(governed);
        blocked.removeAll(anyVisible);
        Set<String> perRow = new java.util.HashSet<>(anyVisible);
        if (allVisible != null) perRow.removeAll(allVisible);
        Map<Set<String>, List<Grant>> readersByFields = new java.util.HashMap<>();
        if (!perRow.isEmpty()) {
            visibleBy.forEach((g, visible) -> {
                Set<String> fields = new java.util.HashSet<>(visible);
                fields.retainAll(perRow);
                if (!fields.isEmpty()) {
                    readersByFields.computeIfAbsent(fields, k -> new ArrayList<>()).add(g);
                }
            });
        }
        return new FieldPlan(java.util.Collections.unmodifiableSet(blocked),
                java.util.Collections.unmodifiableSet(perRow), java.util.Collections.unmodifiableMap(readersByFields));
    }

    // ─────────────────────── label only ───────────────────────

    /** Reads, as opposed to writes: what a caller is shown of a record it may not have been granted. */
    private static final Set<AccessType> READS = EnumSet.of(AccessType.READ, AccessType.EXPORT);

    /**
     * Whether a read of {@code model} can come down to its label for some role: a read, of a model a role
     * can be granted in its own right or one owned by such a model, and not declared public
     * configuration. A model no role can be granted and nothing owns is left whole — nobody could see more
     * of it than anybody else.
     */
    private boolean mayBeLabelOnly(String model, AccessType access) {
        if (!READS.contains(access) || model == null || !ModelManager.existModel(model)) return false;
        if (declaredScope(model) == ScopeType.ALL) return false;
        return !directViewPermissions(model).isEmpty() || !ownersOf(model).isEmpty();
    }

    /**
     * Whether {@code g} shows {@code model} in full: it was granted the model itself — not a lookup its
     * own model's relations derive — or the model is owned by one it shows in full. Otherwise the role
     * reaches the model only as the target of a reference, and shows its label.
     */
    private boolean viewsInFull(Grant g, String model, int depth) {
        // Granted, not merely left whole: a model no role can be granted is left whole for itself, and
        // that must not carry down to what it owns — UserProfile owns the accounts, and is granted to
        // no one.
        Set<String> direct = directViewPermissions(model);
        if (g.permissions().stream().anyMatch(direct::contains)) return true;
        if (depth >= MAX_OWNER_DEPTH) return false;
        for (String owner : ownersOf(model)) {
            if (viewsInFull(g, owner, depth + 1)) return true;
        }
        return false;
    }

    private static final int MAX_OWNER_DEPTH = 4;

    private Set<String> directViewPermissions(String model) {
        EndpointIndex index = endpointIndexSupplier.get();
        return index == null ? Set.of() : index.directModelActionPermissions(model, "view");
    }

    /**
     * The label of {@code model}: what any reference to one of its rows shows — the id, the display name,
     * the business key (an import finds a related row by it), the tree path, the active flag a picker
     * filters on.
     */
    private static Set<String> labelFields(String model) {
        Set<String> labels = new java.util.HashSet<>();
        labels.add(ModelConstant.ID);
        labels.addAll(ModelManager.getModelDisplayName(model));
        MetaModel meta = ModelManager.getModel(model);
        if (meta.getBusinessKey() != null) labels.addAll(meta.getBusinessKey());
        if (ModelManager.existField(model, "idPath")) labels.add("idPath");
        if (meta.isActiveControl()) labels.add(ModelConstant.ACTIVE_CONTROL_FIELD);
        return labels;
    }

    private static Set<String> pastLabelFields(String model) {
        Set<String> fields = new java.util.HashSet<>(ModelManager.getModelStoredFields(model));
        fields.removeAll(labelFields(model));
        return fields;
    }

    /** Models owning {@code model}: one-to-one owners (not dependants deleted with it), one-to-many parents. */
    private Set<String> ownersOf(String model) {
        return ownerModels().getOrDefault(model, Set.of());
    }

    private volatile Map<String, Set<String>> ownerModelsCache;
    private volatile Set<String> cascadeExposedCache;

    private Map<String, Set<String>> ownerModels() {
        Map<String, Set<String>> cached = ownerModelsCache;
        if (cached != null) return cached;
        Map<String, Set<String>> owners = new java.util.HashMap<>();
        for (String owner : ModelManager.getModelNames()) {
            for (MetaField f : ModelManager.getModelFields(owner)) {
                String related = f.getRelatedModel();
                if (related == null || related.equals(owner)) continue;
                boolean ownsOne = f.getFieldType() == FieldType.ONE_TO_ONE && f.getOnDelete() != OnDelete.CASCADE;
                boolean ownsMany = f.getFieldType() == FieldType.ONE_TO_MANY && StringUtils.isNotBlank(f.getRelatedField());
                if (ownsOne || ownsMany) {
                    owners.computeIfAbsent(related, k -> new java.util.HashSet<>()).add(owner);
                }
            }
        }
        ownerModelsCache = owners;
        return owners;
    }

    /** {@code Model.field} pairs some model's declared cascaded field reads. */
    private Set<String> cascadeExposed() {
        Set<String> cached = cascadeExposedCache;
        if (cached != null) return cached;
        Set<String> exposed = new java.util.HashSet<>();
        for (String model : ModelManager.getModelNames()) {
            for (MetaField f : ModelManager.getModelFields(model)) {
                if (StringUtils.isBlank(f.getCascadedField())) continue;
                PathTarget target = resolvePath(model, f.getCascadedField());
                if (target != null) exposed.add(target.model() + "." + target.leaf());
            }
        }
        cascadeExposedCache = exposed;
        return exposed;
    }

    /**
     * @param blocked fields hidden on every row
     * @param perRow fields shown only on the rows of the roles granting them
     * @param readersByFields for each distinct set of per-row fields, the roles granting exactly it
     */
    private record FieldPlan(Set<String> blocked, Set<String> perRow,
                             Map<Set<String>, List<Grant>> readersByFields) {
        static final FieldPlan NONE = new FieldPlan(Set.of(), Set.of(), Map.of());

        boolean isEmpty() {
            return blocked.isEmpty() && perRow.isEmpty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>"Can't see on every row" is the field plan's blocked plus per-row — for the model's own fields,
     * and for another model's reached through a to-one path such as {@code employeeId.salary}, by that
     * model's plan. Masked values sorted among visible ones would place each hidden figure between its
     * neighbours. The sort is dropped rather than refused, as a list a user merely clicked a header on
     * should still load; a grouping or aggregate is refused, since dropping it would change what the
     * result means.
     */
    @Override
    public void guardQuery(String model, FlexQuery flexQuery) {
        if (flexQuery == null || shouldBypass()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return;
        AccessType read = readAccess(AccessType.READ);
        Map<String, FieldPlan> plans = new java.util.HashMap<>();
        // A declared cascaded field is judged by the path it stands for, and a path into a related row the
        // caller reads under a range — past its label — is held back as a masked field is: sorted or
        // grouped by, it would order or bucket the rows by values the caller may not read.
        java.util.function.Predicate<String> masked = field -> {
            if (field == null) return false;
            String path = expandAlias(model, field);
            return (sfsCache != null && maskedTarget(pi, model, path, read, plans) != null)
                    || readsBeyondRange(pi, model, path);
        };
        Orders orders = flexQuery.getOrders();
        if (orders != null && orders.getFields().stream().anyMatch(masked)) {
            Orders kept = new Orders();
            for (List<String> unit : orders.getOrderList()) {
                if (unit.isEmpty() || masked.test(unit.getFirst())) continue;
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
            if (masked.test(field)) {
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
     * <p>Judged by the same grant pairs the reads and writes are, and by fields, as the mask is. A
     * sensitive set is <b>hidden</b> on a record when none of its fields is granted by a reading role that
     * reaches the record; <b>read-only</b> when some are, but none by an editing role that reaches it. A
     * field shared by two sets therefore keeps both sets visible when a role grants either — the form
     * never hides a section whose values the read returns. An action is available where the caller holds
     * it at all and a role holding it reaches the record. One query per role involved, whatever the
     * number of records.
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
                out.add(new RecordAccess(id, owned, Set.of(), Set.of(),
                        fieldsMissing(owned, List.of(), null), Map.of()));
                continue;
            }
            List<Grant> editing = held.contains(AccessType.UPDATE)
                    ? editors.stream().filter(g -> reach.get(g).contains(key)).toList()
                    : List.of();
            Set<String> hidden = new java.util.TreeSet<>();
            Set<String> readonly = new java.util.TreeSet<>();
            for (String set : owned) {
                if (!grantsAnyFieldOf(reading, model, set)) {
                    hidden.add(set);
                } else if (!grantsAnyFieldOf(editing, model, set)) {
                    readonly.add(set);
                }
            }
            Set<AccessType> actions = EnumSet.noneOf(AccessType.class);
            if (!editing.isEmpty()) actions.add(AccessType.UPDATE);
            if (held.contains(AccessType.DELETE) && deleters.stream().anyMatch(g -> reach.get(g).contains(key))) {
                actions.add(AccessType.DELETE);
            }
            Map<String, Set<String>> hiddenFields = fieldsMissing(owned, reading, null);
            out.add(new RecordAccess(id, hidden, readonly, actions,
                    hiddenFields, fieldsMissing(owned, editing, hiddenFields)));
        }
        return out;
    }

    /**
     * The fields of {@code sets}, by the model each set belongs to, that none of {@code grants} grants —
     * leaving out those already in {@code except}. Judged field by field, as the mask and the write
     * check are: a field two sets share is let through by a role granting either set. Empty without
     * the set catalogue.
     */
    private Map<String, Set<String>> fieldsMissing(Set<String> sets, List<Grant> grants,
                                                   Map<String, Set<String>> except) {
        if (sfsCache == null || sets.isEmpty()) return Map.of();
        Map<String, Set<String>> out = new java.util.TreeMap<>();
        for (String set : sets) {
            String owner = sfsCache.modelOf(set);
            if (owner != null) {
                out.computeIfAbsent(owner, k -> new java.util.TreeSet<>()).addAll(sfsCache.fieldCodesOf(set));
            }
        }
        for (Map.Entry<String, Set<String>> entry : out.entrySet()) {
            for (Grant g : grants) {
                Set<String> held = g.sensitiveSets().getOrDefault(entry.getKey(), Set.of());
                if (!held.isEmpty()) entry.getValue().removeAll(sfsCache.grantedFieldsFor(entry.getKey(), held));
            }
            if (except != null) entry.getValue().removeAll(except.getOrDefault(entry.getKey(), Set.of()));
        }
        out.values().removeIf(Set::isEmpty);
        return out;
    }

    /** {@inheritDoc} — a set is shown on a create form when some role that may create the model grants
     *  one of its fields. */
    @Override
    public CreateAccess getCreateAccess(String model) {
        if (shouldBypass() || sfsCache == null) return new CreateAccess(Set.of());
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return new CreateAccess(Set.of());
        Set<String> hidden = new java.util.TreeSet<>(sfsCache.setIdsOwnedBy(model));
        hidden.addAll(sfsCache.setIdsAttachedTo(model));
        List<Grant> creators = holders(pi, model, AccessType.CREATE);
        hidden.removeIf(set -> grantsAnyFieldOf(creators, model, set));
        Set<String> all = new java.util.TreeSet<>(sfsCache.setIdsOwnedBy(model));
        all.addAll(sfsCache.setIdsAttachedTo(model));
        return new CreateAccess(hidden, fieldsMissing(all, creators, null));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same answer {@link #checkWritePayload} refuses by: per model, the sensitive fields none of
     * the caller's roles grants.
     */
    @Override
    public Map<String, Set<String>> getHiddenSensitiveFields() {
        if (shouldBypass() || sfsCache == null) return Map.of();
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return Map.of();
        Map<String, Set<String>> hidden = new java.util.TreeMap<>();
        for (String model : sfsCache.modelsWithSensitiveFields()) {
            Set<String> blocked = blockedForAnyRole(pi, model);
            if (!blocked.isEmpty()) hidden.put(model, new java.util.TreeSet<>(blocked));
        }
        return hidden;
    }

    /**
     * Whether one of {@code grants} grants a field of {@code set}, on the set's own model — {@code model}
     * when unknown. A field is granted through any set covering it, so a set sharing a field with a
     * granted one counts as granted for that field. A set whose fields are unknown falls back to being
     * granted itself.
     */
    private boolean grantsAnyFieldOf(List<Grant> grants, String model, String set) {
        if (grants.isEmpty()) return false;
        String known = sfsCache == null ? null : sfsCache.modelOf(set);
        String setModel = known != null ? known : model;
        Set<String> fields = sfsCache == null ? Set.of() : sfsCache.grantedFieldsFor(setModel, Set.of(set));
        for (Grant g : grants) {
            Set<String> held = g.sensitiveSets().getOrDefault(setModel, Set.of());
            if (held.contains(set)) return true;
            if (fields.isEmpty() || held.isEmpty()) continue;
            for (String field : sfsCache.grantedFieldsFor(setModel, held)) {
                if (fields.contains(field)) return true;
            }
        }
        return false;
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
        forgetScopes();
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
            Filters scope = anyOf(grantingWriters.stream()
                    .map(g -> judgedOnOwner(g, model, accessType) ? null : grantScope(g, model))
                    .toList());
            if (scope == null) return;
            Filters target = Filters.and(Filters.of(ModelConstant.ID, Operator.IN, idList), scope);
            long reached = unscoped(() -> modelService.count(model, target));
            if (reached != idList.size()) {
                throw sensitiveWriteDenied(model, written.getFirst());
            }
        });
    }

    /**
     * Named the way a user sees them: the set's short name ("Pay") and the model ("employee") —
     * "You don't have permission to edit Pay fields for this employee." A file import reads "update",
     * since the uploader is not editing anything.
     */
    private PermissionException sensitiveWriteDenied(String model, String field) {
        String set = sfsCache.setIdsContaining(model, field).stream()
                .map(sfsCache::shortNameOf)
                .filter(StringUtils::isNotBlank)
                .sorted()
                .findFirst()
                .orElse(field);
        String label = ModelManager.existModel(model) && StringUtils.isNotBlank(ModelManager.getModel(model).getLabel())
                ? inSentence(ModelManager.getModel(model).getLabel())
                : model;
        String verb = ImportScope.isActive() ? "update" : "edit";
        return new PermissionException("You don't have permission to " + verb + " " + set + " fields for this " + label + ".");
    }

    /**
     * A label as it reads mid-sentence: each word's first letter lowered, unless the word is an acronym
     * — its second letter a capital too — which keeps its case. "Leave Request" reads "leave request",
     * "HR Case" reads "HR case".
     */
    static String inSentence(String label) {
        StringBuilder out = new StringBuilder(label.length());
        for (String word : label.split(" ", -1)) {
            if (!out.isEmpty()) out.append(' ');
            boolean acronym = word.length() > 1 && Character.isUpperCase(word.charAt(1));
            out.append(acronym || word.isEmpty()
                    ? word
                    : Character.toLowerCase(word.charAt(0)) + word.substring(1));
        }
        return out.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>A null is not refused here. On a create it writes nothing; on an update it clears the stored
     * value or, where that is already null, changes nothing — which of the two is known only once the
     * stored row is, so the update path checks it then ({@link #getWriteGuardedFields},
     * {@link #checkIdsFieldsAccess}).
     */
    @Override
    public void checkWritePayload(String model, Map<String, Object> payload) {
        forgetScopes();
        if (payload == null || payload.isEmpty() || shouldBypass()) return;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.hasFullDataAccess(pi)) return;
        // Before the write, the rows are not known: refuse only what no role grants at all. Which rows
        // a granted field may be written on is checked once the ids are (checkIdsFieldsAccess).
        Set<String> blocked = blockedForAnyRole(pi, model);
        if (blocked.isEmpty()) return;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getValue() != null && blocked.contains(entry.getKey())) {
                throw sensitiveWriteDenied(model, entry.getKey());
            }
        }
    }

    /** {@inheritDoc} — the model's sensitive fields, for a caller whose writes of them are judged at all. */
    @Override
    public Set<String> getWriteGuardedFields(String model) {
        if (shouldBypass() || sfsCache == null || !sfsCache.hasSensitiveFieldsOn(model)) return Set.of();
        if (PermissionInfo.hasFullDataAccess(currentPi())) return Set.of();
        return sfsCache.allSensitiveFieldsOn(model);
    }

    /** Sensitive fields on {@code model} that none of the caller's roles grants. */
    private Set<String> blockedForAnyRole(PermissionInfo pi, String model) {
        if (sfsCache == null || !sfsCache.hasSensitiveFieldsOn(model)) return Set.of();
        Set<String> granted = new java.util.HashSet<>();
        for (Grant g : grantsOf(pi)) {
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
        if (accessType != AccessType.READ) {
            forgetScopes();
        }
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
     *       points at. Not on CREATE: see {@link #judgedOnOwner};</li>
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
        if (judgedOnOwner(g, model, accessType)) {
            return null;
        }
        List<Referencer> refs = findReferencers(model, g);
        Kind kind = refs.isEmpty() ? null : refs.getFirst().kind();
        if (kind == Kind.OWNED_ONE_TO_ONE) {
            return followOwners(refs, g);
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
     * Whether a write to {@code model} by this grant has its row range judged on the owner instead of
     * on the rows themselves: a one-to-one row being created, with no rules of its own for the model.
     *
     * <p>Such a row is in range when an in-range owner points at it, and when it is created nested in
     * its owner's payload it is written first — its id is what the owner's foreign key is set to — so
     * at its own check nothing points at it yet, and every new one fell outside the range: a role
     * reaching its own department could not add an employee, because the profile in the same request
     * was refused. Its range is decided where it becomes reachable, by the owner's own create or
     * update, which is checked against the owner's range. A new row nothing ever points at is reachable
     * by no one. Updating, deleting or naming an existing row by id keeps its own check, since that row
     * may already belong to an owner outside the range.
     */
    private boolean judgedOnOwner(Grant g, String model, AccessType accessType) {
        if (!AccessType.CREATE.equals(accessType) || hasExplicitRules(g, model)) {
            return false;
        }
        List<Referencer> refs = findReferencers(model, g);
        return !refs.isEmpty() && refs.getFirst().kind() == Kind.OWNED_ONE_TO_ONE;
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
        return new java.util.HashSet<>(fieldPlan(pi, model, readAccess(accessType)).blocked());
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
     * The role's company grant on {@code model} as a filter, {@code null} when it does not narrow.
     * {@link #grantScope} ANDs it onto that role's rows.
     *
     * <p>Reads the anchor from the model's metadata rather than assuming a field name, so a model that
     * reaches its company through another one — a per-department statistic declaring {@code companyId}
     * as a dynamic cascaded field — is bounded by the same grant as a model that owns the column.
     *
     * <p>A {@code null} grant means unrestricted. That is the opt-in convention, not an oversight: the
     * alternative empties every screen for every role that predates company grants.
     *
     * <p>No read that names rows by {@code id} is exempt — the scope does not look at the caller's
     * filters at all. Display expansion — the one legitimate by-id read of rows outside the grant, so
     * that a referenced row's label is not blanked — never reaches here: it reads through {@code
     * ModelService.searchListIgnoringRowScope}, which leaves the row range out. Everything else that
     * names an id stays bounded: otherwise a caller could add
     * {@code ["id", "&gt;", 0]} to a {@code searchList} body and read an ungranted company whole, and
     * {@link #checkIdsAccess} — which counts its targets back with a filter naming {@code id} — would
     * let any holder of the delete permission remove a row of a company they were never granted.
     */
    // Package-private for test: the empty-grant default and the path-anchored case both fail silently.
    Filters companyScope(String model, Set<Long> granted) {
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
     * right, so {@link #findReferencers} — which scans the GRANTED models — finds nothing and the read
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
     *  through a parent instead (see {@link #findReferencers}). */
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
     *   <li>{@code ONE_TO_ONE} owners → follow those owners' row-scope
     *       ({@link Kind#OWNED_ONE_TO_ONE}); the FK lives on the PARENT and holds the
     *       child's id. A referrer whose FK is {@code onDelete = CASCADE} is not an owner: its
     *       row is deleted with the child, so it hangs off the child (a report row over a
     *       person, a credential over a profile) and granting it in full must not open the child;</li>
     *   <li>{@code ONE_TO_MANY} parents → follow those parents' row-scope
     *       ({@link Kind#CHILD_BY_BACKREF}); the FK lives on the CHILD
     *       ({@code relatedField}) and holds the parent's id — the inverse direction,
     *       so the two cannot share one filter shape;</li>
     *   <li>else a {@code MANY_TO_ONE} referrer → shared reference/config
     *       ({@link Kind#SHARED});</li>
     *   <li>failing all three, a {@code MANY_TO_ONE} referrer on an <b>owned child</b> of a granted
     *       model → also shared ({@link #sharedThroughOwnedChild});</li>
     *   <li>none → empty (not reachable from any grant → stays fail-closed).</li>
     * </ul>
     *
     * <p>Every owner of the winning kind is returned, not the first one met: which granted model the
     * scan reaches first is an accident of iteration order, and a child owned twice (a person's profile
     * by the employee record and by a report over it) must resolve the same way however the grant
     * happens to hold its models. {@link #followOwners} takes the union.
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
    private List<Referencer> findReferencers(String childModel, Grant g) {
        List<Referencer> owners = new ArrayList<>();
        List<Referencer> backRefs = new ArrayList<>();
        Referencer shared = null;
        List<String> ownedChildren = new ArrayList<>();
        // Sorted, so the answer never depends on the order the grant happens to hold its models in.
        for (String granted : new TreeSet<>(g.scopes().keySet())) {
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
                    // A row deleted along with the one it points at hangs off that row — a report row
                    // or a credential over a person, say. It is a dependant of the child, not its owner,
                    // and reading the dependant model in full must not open the child.
                    if (f.getOnDelete() != OnDelete.CASCADE) {
                        owners.add(new Referencer(granted, f.getFieldName(), Kind.OWNED_ONE_TO_ONE));
                    }
                    continue;
                }
                if (f.getFieldType() == FieldType.ONE_TO_MANY && StringUtils.isNotBlank(f.getRelatedField())) {
                    backRefs.add(new Referencer(granted, f.getRelatedField(), Kind.CHILD_BY_BACKREF));
                }
                if (f.getFieldType() == FieldType.MANY_TO_ONE && shared == null) {
                    shared = new Referencer(granted, f.getFieldName(), Kind.SHARED);
                }
            }
        }
        if (!owners.isEmpty()) return owners;
        if (!backRefs.isEmpty()) return backRefs;
        if (shared != null) return List.of(shared);
        Referencer viaOwnedChild = sharedThroughOwnedChild(childModel, ownedChildren);
        return viaOwnedChild == null ? List.of() : List.of(viaOwnedChild);
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
        String action = MODEL_ACTIONS.get(accessType);
        if (action == null) return true;
        return holdsAny(pi, endpointIndex.modelActionPermissions(model, action));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Asked by the action segment of the permission id, the way {@link #actionPermissions} asks for an
     * export: no endpoint path names the model.
     */
    @Override
    public boolean hasModelImportGrant(String model) {
        if (shouldBypass() || model == null) return true;
        EndpointIndex endpointIndex = endpointIndexSupplier.get();
        if (endpointIndex == null) return true;
        PermissionInfo pi = currentPi();
        if (PermissionInfo.isAdmin(pi)) return true;
        return holdsAny(pi, endpointIndex.actionPermissions(model, "import"));
    }

    /** Whether the caller holds one of {@code candidates}; an empty set — nothing registered — grants. */
    private static boolean holdsAny(PermissionInfo pi, Set<String> candidates) {
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
     * The action segment of a permission id for each access type a model's own endpoints serve — the
     * key {@link EndpointIndex#modelActionPermissions} answers by.
     */
    private static final Map<AccessType, String> MODEL_ACTIONS = Map.of(
            AccessType.CREATE, "create",
            AccessType.UPDATE, "update",
            AccessType.DELETE, "delete",
            AccessType.READ, "view");
}
