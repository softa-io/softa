package io.softa.starter.flow.runtime.task.builtin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.flow.runtime.task.TaskExecutor;

import static io.softa.framework.orm.constant.ModelConstant.ID;

/**
 * Base class for builtin {@link TaskExecutor}s, centralizing the input-coercion and
 * placeholder-resolution helpers that were previously copy-pasted across executors.
 */
public abstract class AbstractTaskExecutor implements TaskExecutor {

    private static final Pattern EXACT_INTERPOLATION = Pattern.compile("^\\{\\{\\s*(.+?)\\s*}}$");

    /**
     * Recursively resolve {@code {{ }}} placeholders in a schemaless payload against the execution
     * scope: an exact {@code {{ expr }}} yields the raw evaluated object, a string with embedded
     * placeholders is interpolated, and maps / lists recurse. Executors whose payload has no fixed
     * shape (e.g. WebHook headers / body) call this explicitly — it replaces the handler's former
     * blanket pre-resolution, which also (wrongly) shadowed the data executors' type-aware resolution.
     */
    protected Object interpolate(Object value, Map<String, Object> scope) {
        if (value instanceof String s) {
            Matcher matcher = EXACT_INTERPOLATION.matcher(s);
            if (matcher.matches()) {
                return ComputeUtils.execute(matcher.group(1), new LinkedHashMap<>(scope));
            }
            if (s.contains("{{")) {
                return ComputeUtils.stringInterpolation(s, new LinkedHashMap<>(scope));
            }
            return s;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), interpolate(v, scope)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> interpolate(item, scope)).toList();
        }
        return value;
    }

    /**
     * Obtain a migrated executor's typed input config: the handler-parsed {@link TaskExecutionRequest#getConfig()}
     * when present, else (for direct callers / unit tests that pass a raw {@code input} map) parsed from it. This
     * is the single point where a typed executor turns wire config into its DTO — executors themselves stay
     * Map-free.
     */
    protected <T> T requireConfig(TaskExecutionRequest request, Class<T> type) {
        Object cfg = request.getConfig();
        if (type.isInstance(cfg)) {
            return type.cast(cfg);
        }
        Map<String, Object> input = request.getInput() == null ? Map.of() : request.getInput();
        return BeanTool.objectToObject(input, type);
    }

    /** Interpolate {@code {{ }}} in a required scalar string (owned by the executor now, not the handler) and assert non-blank. */
    protected String requireResolvedString(String raw, String key, Map<String, Object> scope) {
        String resolved = resolveString(raw, scope);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException("Required input." + key + " must not be blank");
        }
        return resolved;
    }

    /** Asserts a required typed-config string field is present; same contract as requireString. */
    protected String requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required input." + key + " must not be blank");
        }
        return value;
    }

    protected String asString(Object value) {
        return value == null ? null : value.toString();
    }

    protected Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }

    /** Resolves a value, applying {{ }} interpolation when it is a String containing placeholders. */
    protected String resolveString(Object value, Map<String, Object> scope) {
        if (value == null) return null;
        String s = value.toString();
        if (s.contains("{{")) {
            return ComputeUtils.stringInterpolation(s, new LinkedHashMap<>(scope));
        }
        return s;
    }

    protected List<String> resolveStringList(Object value, Map<String, Object> scope) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String resolved = resolveString(item, scope);
                if (resolved != null && !resolved.isBlank()) {
                    result.add(resolved);
                }
            }
            return result;
        }
        String resolved = resolveString(value, scope);
        if (resolved != null && !resolved.isBlank()) {
            return List.of(resolved);
        }
        return List.of();
    }

    protected Map<String, Object> resolveVariableMap(Map<String, Object> vars, Map<String, Object> scope) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        vars.forEach((key, val) -> {
            if (val instanceof String s && s.contains("{{")) {
                resolved.put(key, ComputeUtils.stringInterpolation(s, new LinkedHashMap<>(scope)));
            } else {
                resolved.put(key, val);
            }
        });
        return resolved;
    }

    /**
     * Build a {@link Filters} from an optional primary-key variable (resolved to an {@code id IN (...)}
     * clause) merged via AND with the {@code input.filters} condition. Returns an empty Filters when a
     * pkVariable is supplied but resolves to no ids. Shared by the Update/Delete data executors.
     */
    protected Filters buildFilters(String modelName, String pkVariable,
                                   Object filtersObj, Map<String, Object> variables) {
        Filters filters = new Filters();
        if (StringUtils.hasText(pkVariable)) {
            Collection<?> ids = VariableResolver.getIdsFromPkVariable(pkVariable, variables);
            if (CollectionUtils.isEmpty(ids)) {
                return filters;
            }
            filters.in(ID, ids);
        }
        Filters configured = resolveFilters(modelName, filtersObj, variables);
        if (configured != null && !Filters.isEmpty(configured)) {
            filters.and(configured);
        }
        return filters;
    }

    /**
     * Resolve the optional {@code filters} condition into a placeholder-substituted {@link Filters}.
     * The value arrives from flow JSON as a raw list/string (e.g. {@code ["deptId","=","{{ deptId }}"]})
     * — never a typed Filters in production. It is coerced via {@link Filters}'s registered deserializer,
     * then {@link VariableResolver#resolveFilterValue} substitutes {@code {{ }}} placeholders. Returns
     * null when no filters are configured. Shared by the Get/Query/Update/Delete data executors.
     */
    protected Filters resolveFilters(String modelName, Object filtersObj, Map<String, Object> variables) {
        Filters filters = coerceFilters(filtersObj);
        if (filters == null) {
            return null;
        }
        VariableResolver.resolveFilterValue(modelName, filters, variables);
        return filters;
    }

    /** Coerce a raw config value (typed Filters, JSON list, or filter string) into a defensive Filters copy, or null. */
    private Filters coerceFilters(Object filtersObj) {
        if (filtersObj == null) {
            return null;
        }
        if (filtersObj instanceof Filters f) {
            return f.deepCopy();
        }
        return BeanTool.objectToObject(filtersObj, Filters.class);
    }

    /**
     * Coerce the optional {@code input.orders} value (a typed Orders, a JSON list, or an orders string
     * like {@code "name ASC"}) into a typed {@link Orders}, or null when unset.
     */
    protected Orders resolveOrders(Object ordersObj) {
        if (ordersObj == null) {
            return null;
        }
        if (ordersObj instanceof Orders o) {
            return o;
        }
        return BeanTool.objectToObject(ordersObj, Orders.class);
    }
}
