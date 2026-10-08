package io.softa.starter.permission.aspect;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.BeanUtils;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.AccessScope;
import io.softa.framework.orm.service.PermissionService;
import lombok.RequiredArgsConstructor;

/**
 * Enforces {@link io.softa.starter.permission.annotation.RequirePermission}:
 * verify the caller's row scope on the endpoint's main model, THEN open
 * {@code Context.skipPermissionCheck} for the endpoint body.
 *
 * <h3>Order is the security property</h3>
 * The main-model check runs while the flag is still CLOSED, so
 * {@code checkIdsAccess} sees the caller's real scope; the flag opens only
 * after every declared scope has passed — same discipline as
 * {@code @RequireRole} ("enabled ONLY after verified — never before") — and
 * is restored in a finally block, so the bypass cannot leak past this call.
 *
 * <h3>How wide the bypass is</h3>
 * It is the framework's single bypass flag, so inside the endpoint body every
 * layer is off — row scope, field-level guards and sensitive-field masking
 * alike. Field-level confidentiality is not enforced through this path: once
 * the entry check has passed, the body is trusted business logic ("the endpoint
 * is the resource"). An endpoint that needs masking on its own response must
 * not declare this annotation and should check scope by hand.
 *
 * <h3>Filter rewrite, not filter check</h3>
 * A declared {@code filterParam} argument is REPLACED with itself AND-ed with
 * the caller's scope ({@code appendScopeAccessFilters}) before proceeding.
 * With the flag then open, the service's own {@code searchList} won't stack a
 * second scope on top — the entry rewrite is the single application.
 *
 * <p>Resolution (model inference, parameter lookup) is cached per method and
 * validated at startup by {@link RequirePermissionStartupValidator}; a resolution
 * failure here is therefore a programming error, not a request-time condition.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RequirePermissionAspect {

    private final PermissionService permissionService;

    private final Map<Method, List<ResolvedScope>> cache = new ConcurrentHashMap<>();

    /** One declared scope, resolved against the concrete method. {@code idPathAccessors}
     *  is the startup-compiled getter chain for {@code idPath} (null = idParam holds
     *  the ids directly), so request time is pure {@code invoke}, no lookups. */
    record ResolvedScope(String model, int idIndex, int filterIndex,
                         List<java.lang.reflect.Method> idPathAccessors, String idPath, AccessType accessType) {
    }

    @Around("@annotation(io.softa.starter.permission.annotation.RequirePermission)"
            + " || @annotation(io.softa.starter.permission.annotation.RequirePermissions)")
    public Object enforce(ProceedingJoinPoint jp) throws Throwable {
        Method method = ((MethodSignature) jp.getSignature()).getMethod();
        List<ResolvedScope> scopes = cache.computeIfAbsent(method, RequirePermissionAspect::resolve);
        Object[] args = jp.getArgs();

        // ── ① verify / rewrite — flag still closed, real scope applies
        for (ResolvedScope scope : scopes) {
            if (scope.idIndex() >= 0) {
                Collection<? extends Serializable> ids = scope.idPathAccessors() != null
                        ? extractByPath(args[scope.idIndex()], scope.idPathAccessors(), scope.idPath())
                        : idsOf(args[scope.idIndex()]);
                // De-duplicate before the check: checkIdsAccess compares the scoped
                // count against the RAW list size, so two documents naming the same
                // employee ([7, 7] vs count 1) would false-reject a legitimate call.
                ids = ids.stream().distinct().toList();
                // Fail closed on a null/empty id argument. checkIdsAccess returns
                // silently for an empty collection, so letting it through would
                // open the bypass with NOTHING verified — and an endpoint that can
                // locate data by other parameters (a code, an employeeId) would
                // then be reachable at full range simply by omitting the id.
                // An endpoint whose id is legitimately optional must not declare
                // idParam for it — declare filterParam or split the endpoint.
                if (ids.isEmpty()) {
                    throw new PermissionException("Parameter '"
                            + method.getParameters()[scope.idIndex()].getName()
                            + "' is required: the main-model scope check on "
                            + scope.model() + " has nothing to verify without it.");
                }
                permissionService.checkIdsAccess(scope.model(), ids, scope.accessType());
            }
            if (scope.filterIndex() >= 0) {
                Filters original = (Filters) args[scope.filterIndex()];
                Filters base = original == null ? new Filters() : original;
                args[scope.filterIndex()] = AccessScope.callAs(scope.accessType(),
                        () -> permissionService.appendScopeAccessFilters(scope.model(), base));
            }
        }

        // ── ② bypass — only now
        if (!ContextHolder.existContext()) {
            // Unbound context (scheduler / MQ threads): shouldBypass() is already
            // true downstream, the flag would land on a throwaway Context anyway.
            return jp.proceed(args);
        }
        Context ctx = ContextHolder.getContext();
        boolean previous = ctx.isSkipPermissionCheck();
        try {
            ctx.setSkipPermissionCheck(true);
            return jp.proceed(args);
        } finally {
            ctx.setSkipPermissionCheck(previous);
        }
    }

    // ─────────────────────── resolution ───────────────────────

    /**
     * Resolve every declared {@code @RequirePermission} against the method:
     * infer the model when omitted, locate the named parameters. Throws
     * {@link IllegalStateException} with the concrete fix on any mismatch —
     * called at startup by the validator, so misconfiguration fails the boot,
     * not the first request.
     */
    static List<ResolvedScope> resolve(Method method) {
        var declared = method.getAnnotationsByType(
                io.softa.starter.permission.annotation.RequirePermission.class);
        if (declared.length == 0) {
            // The pointcut matched but this Method carries no resolvable
            // annotation (interface-proxied / bridge method). Proceeding would
            // open the bypass with zero checks — fail instead. If this fires,
            // move the annotation onto the method the proxy actually exposes.
            throw new IllegalStateException("@RequirePermission pointcut matched " + method
                    + " but no annotation is resolvable on it — refusing to open the"
                    + " scope bypass unchecked.");
        }
        List<ResolvedScope> out = new ArrayList<>(declared.length);
        for (var scope : declared) {
            String model = scope.model().isEmpty() ? inferModel(method) : scope.model();
            assertModelKnown(model, method, scope.model().isEmpty());
            boolean hasPath = !scope.idPath().isEmpty();
            if (hasPath && scope.idParam().isEmpty()) {
                throw new IllegalStateException("@RequirePermission on " + method
                        + " declares idPath without idParam — idPath navigates INSIDE the"
                        + " parameter idParam names. Declare both.");
            }
            int idIndex = scope.idParam().isEmpty() ? -1
                    : paramIndex(method, scope.idParam(), null, /* holdsIdsDirectly = */ !hasPath);
            int filterIndex = scope.filterParam().isEmpty() ? -1
                    : paramIndex(method, scope.filterParam(), Filters.class, true);
            if (idIndex < 0 && filterIndex < 0) {
                throw new IllegalStateException("@RequirePermission on " + method
                        + " declares neither idParam nor filterParam — nothing to check."
                        + " Declare at least one, or drop the annotation.");
            }
            List<java.lang.reflect.Method> accessors = hasPath
                    ? compilePath(method.getParameters()[idIndex].getType(), scope.idPath(), method)
                    : null;
            out.add(new ResolvedScope(model, idIndex, filterIndex, accessors, scope.idPath(), scope.accessType()));
        }
        return out;
    }

    /**
     * Reject a model name the registry does not know — a typo in {@code model = "Employe"}, or a
     * path whose first segment is not a model name at all ({@code /api/LeaveRequest} infers
     * {@code api}). Without this, every other kind of misconfiguration fails the boot while a wrong
     * model waits until the first request, where it surfaces as an unrelated-looking error.
     *
     * <p>The registry is populated by the time this runs: {@code AppStartup} loads it from an
     * {@code InitializingBean} callback, which the container completes for every singleton before
     * {@code RequirePermissionStartupValidator} — a {@code SmartInitializingSingleton} — resolves
     * anything. So an unknown name here means the name is wrong, not that metadata is late.
     */
    private static void assertModelKnown(String model, Method method, boolean inferred) {
        if (ModelManager.existModel(model)) {
            return;
        }
        throw new IllegalStateException("@RequirePermission on " + method + ": model '" + model
                + "' does not exist"
                + (inferred
                        ? " — it was inferred from the controller's request-mapping path, which"
                          + " apparently does not start with the model name. Declare model=\"...\""
                          + " explicitly."
                        : ". Check the spelling."));
    }

    /**
     * Infer the main model from the controller's class-level request mapping:
     * {@code /LeaveRequest/...} → {@code LeaveRequest}. Aggregate controllers
     * whose path does not start with a model name must declare {@code model}
     * explicitly — path conventions are the same ones {@code EndpointIndex}
     * builds its URI index from, so a path that lies here lies to Layer A too.
     */
    private static String inferModel(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                method.getDeclaringClass(), RequestMapping.class);
        String[] paths = mapping == null ? new String[0] : mapping.path();
        for (String path : paths) {
            for (String segment : path.split("/")) {
                if (!segment.isEmpty() && !segment.startsWith("{")) {
                    return segment;
                }
            }
        }
        throw new IllegalStateException("@RequirePermission on " + method
                + " has no model and the declaring class has no request-mapping"
                + " path to infer it from. Declare model=\"...\" explicitly.");
    }

    private static int paramIndex(Method method, String name, Class<?> requiredType,
                                  boolean holdsIdsDirectly) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals(name)) {
                Class<?> type = parameters[i].getType();
                if (requiredType == null && holdsIdsDirectly
                        && type.isArray() && type.componentType().isPrimitive()) {
                    throw new IllegalStateException("@RequirePermission on " + method
                            + ": idParam '" + name + "' is a primitive array (" + type.getSimpleName()
                            + ") — its elements cannot be read as ids. Use Long[] / List<Long>.");
                }
                if (requiredType == null && holdsIdsDirectly) {
                    // A Collection idParam is cast to Collection<? extends Serializable> unchecked at
                    // request time, so a List<SomeDto> passes the boot and its elements only fail deep
                    // inside checkIdsAccess. idPath already validates its leaf type — do the same here.
                    assertIdElementType(method, name, parameters[i]);
                }
                if (requiredType != null && !requiredType.isAssignableFrom(parameters[i].getType())) {
                    throw new IllegalStateException("@RequirePermission on " + method
                            + ": parameter '" + name + "' is " + parameters[i].getType().getSimpleName()
                            + ", but only " + requiredType.getSimpleName() + " can be scope-rewritten."
                            + " Ad-hoc DTO/Map conditions must be filtered manually in the service.");
                }
                return i;
            }
        }
        throw new IllegalStateException("@RequirePermission on " + method
                + ": no parameter named '" + name + "'. Available: "
                + Arrays.stream(parameters).map(Parameter::getName).toList()
                + " (parameter names require the -parameters compiler flag).");
    }

    /**
     * A direct {@code idParam} must yield ids, so its declared type has to be a {@code Serializable}
     * (a single id), an array of them, or a {@code Collection} whose ELEMENT type is one. The
     * collection case is the one worth checking: the request-time cast to
     * {@code Collection<? extends Serializable>} is unchecked, so without this a
     * {@code List<SomeDto>} boots fine and only misbehaves once ids reach the scope count.
     *
     * <p>A raw or wildcard {@code Collection} whose element type cannot be resolved is left alone
     * rather than rejected: unlike {@code idPath} — where the whole point of the grammar is
     * compile-time resolvability — a controller signature may legitimately be generic, and failing
     * the boot on it would be stricter than the previous behaviour for no security gain.
     */
    private static void assertIdElementType(Method method, String name, Parameter parameter) {
        Class<?> type = parameter.getType();
        if (!Collection.class.isAssignableFrom(type)) {
            return;   // single id or array — the primitive-array case is rejected above
        }
        Class<?> element = ResolvableType.forMethodParameter(method, indexOf(method, parameter))
                .asCollection().getGeneric(0).resolve();
        if (element == null) {
            return;   // raw / unresolvable generic — see javadoc
        }
        if (!Serializable.class.isAssignableFrom(element) || Collection.class.isAssignableFrom(element)) {
            throw new IllegalStateException("@RequirePermission on " + method
                    + ": idParam '" + name + "' is a Collection of " + element.getSimpleName()
                    + ", which is not a Serializable id. Point idParam at a Collection of ids"
                    + " (List<Long>), or use idPath to navigate into the DTO's id property.");
        }
    }

    private static int indexOf(Method method, Parameter parameter) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == parameter) {
                return i;
            }
        }
        throw new IllegalStateException("Parameter " + parameter + " does not belong to " + method);
    }

    // ─────────────────────── idPath ───────────────────────

    /**
     * Compile an idPath ({@code .} steps into a property, {@code []} expands a
     * Collection) against the parameter's TYPES. Every failure mode is a boot
     * failure with the concrete fix — the point of the restricted grammar over
     * SpEL is exactly that it validates without a live argument.
     */
    static List<java.lang.reflect.Method> compilePath(Class<?> root, String path, Method where) {
        Class<?> current = root;
        List<java.lang.reflect.Method> accessors = new ArrayList<>();
        for (String segment : path.split("\\.", -1)) {
            boolean many = segment.endsWith("[]");
            String prop = many ? segment.substring(0, segment.length() - 2) : segment;
            if (prop.isEmpty()) {
                throw new IllegalStateException("@RequirePermission on " + where
                        + ": idPath '" + path + "' has an empty segment.");
            }
            java.beans.PropertyDescriptor pd = BeanUtils.getPropertyDescriptor(current, prop);
            if (pd == null || pd.getReadMethod() == null) {
                throw new IllegalStateException("@RequirePermission on " + where
                        + ": idPath segment '" + prop + "' does not exist on "
                        + current.getSimpleName() + ". Available: "
                        + Arrays.stream(BeanUtils.getPropertyDescriptors(current))
                                .map(java.beans.PropertyDescriptor::getName)
                                .filter(n -> !"class".equals(n)).toList());
            }
            java.lang.reflect.Method getter = pd.getReadMethod();
            accessors.add(getter);
            if (many) {
                if (!Collection.class.isAssignableFrom(getter.getReturnType())) {
                    throw new IllegalStateException("@RequirePermission on " + where
                            + ": idPath segment '" + prop + "[]' expands elements, but "
                            + prop + " is " + getter.getReturnType().getSimpleName()
                            + ", not a Collection.");
                }
                Class<?> element = ResolvableType.forMethodReturnType(getter)
                        .asCollection().getGeneric(0).resolve();
                if (element == null) {
                    throw new IllegalStateException("@RequirePermission on " + where
                            + ": idPath segment '" + prop + "[]' is a raw Collection —"
                            + " its element type cannot be resolved. Add the generic.");
                }
                current = element;
            } else {
                current = getter.getReturnType();
            }
        }
        if (!Serializable.class.isAssignableFrom(current) || Collection.class.isAssignableFrom(current)) {
            throw new IllegalStateException("@RequirePermission on " + where
                    + ": idPath '" + path + "' leaf is " + current.getSimpleName()
                    + ", not a Serializable id. Point the path at the id property itself.");
        }
        return List.copyOf(accessors);
    }

    /**
     * Walk the compiled getter chain. Fail-closed on any {@code null} along the
     * way: a row whose id is missing cannot be scope-verified, and skipping it
     * would reopen the omit-the-id unchecked-bypass hole.
     */
    static List<Serializable> extractByPath(Object rootArg, List<java.lang.reflect.Method> accessors,
                                            String path) {
        if (rootArg == null) {
            throw new PermissionException("idPath '" + path + "': the request body is null —"
                    + " the main-model scope check has nothing to verify.");
        }
        List<Object> current = List.of(rootArg);
        for (java.lang.reflect.Method accessor : accessors) {
            List<Object> next = new ArrayList<>();
            for (Object node : current) {
                Object value;
                try {
                    value = accessor.invoke(node);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("idPath '" + path + "': "
                            + accessor.getName() + " failed on " + node.getClass().getSimpleName(), e);
                }
                if (value == null) {
                    throw new PermissionException("idPath '" + path + "' hit null at '"
                            + accessor.getName() + "' — a row without its id cannot be"
                            + " scope-verified; refusing instead of skipping it.");
                }
                if (value instanceof Collection<?> c) {
                    // Reject a null ELEMENT here, not on the next hop. Adding it would make it the
                    // next accessor's target, and Method.invoke(null) throws a bare NPE — which is
                    // not a ReflectiveOperationException, so it escapes the catch above and the
                    // caller gets no explanation. Same fail-closed reason as the null value below.
                    for (Object element : c) {
                        if (element == null) {
                            throw new PermissionException("idPath '" + path + "': '"
                                    + accessor.getName() + "' contains a null element — a row"
                                    + " without its id cannot be scope-verified; refusing"
                                    + " instead of skipping it.");
                        }
                        next.add(element);
                    }
                } else {
                    next.add(value);
                }
            }
            current = next;
        }
        List<Serializable> ids = new ArrayList<>(current.size());
        for (Object leaf : current) {
            if (leaf == null) {
                throw new PermissionException("idPath '" + path + "' yielded a null id.");
            }
            ids.add((Serializable) leaf);
        }
        return ids;
    }

    /** Accepts a single id, a collection of ids, or an array of ids. */
    @SuppressWarnings("unchecked")
    private static Collection<? extends Serializable> idsOf(Object arg) {
        if (arg == null) return List.of();
        if (arg instanceof Collection<?> c) return (Collection<? extends Serializable>) c;
        if (arg instanceof Object[] a) {
            return Arrays.stream(a).map(Serializable.class::cast).toList();
        }
        return List.of((Serializable) arg);
    }
}
