package io.softa.starter.flow.runtime.task.builtin;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.nodeconfig.CallServiceConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.flow.runtime.task.TaskExecutor;

/**
 * Built-in executor for {@link FlowNodeType#CALL_SERVICE} nodes — invokes a Spring
 * bean method reflectively.
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "CallService",
 *   "input": {
 *     "beanName": "orderService",
 *     "methodName": "submit",
 *     "args": ["{{ orderId }}", 42],
 *     "argTypes": ["java.lang.String", "int"]   // optional; omitted = best-match by arity
 *   },
 *   "outputVariable": "callResult"
 * }
 * }</pre>
 * <p>
 * <strong>Disabled by default</strong>. Because this node invokes an
 * arbitrary Spring bean method by name, it is opt-in: the builtin is only registered
 * when {@code flow.task.builtin.call-service.enabled=true} is set explicitly. Host
 * applications may instead register their own {@link TaskExecutor} for
 * {@code CALL_SERVICE}.
 * <p>
 * Security: when enabled, {@code flow.task.call-service.allow-list} is
 * <strong>mandatory</strong> — a comma-separated list of permitted bean-name prefixes.
 * Only beans whose name starts with one of these prefixes may be invoked; an
 * empty/unset allow-list fails fast at construction time rather than defaulting to
 * allow-all.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "flow.task.builtin.call-service.enabled",
        havingValue = "true"
)
public class CallServiceTaskExecutor extends AbstractTaskExecutor {

    private static final Pattern EXACT_INTERPOLATION = Pattern.compile("^\\{\\{\\s*(.+?)\\s*}}$");

    private final ApplicationContext applicationContext;
    private final List<String> allowListPrefixes;

    public CallServiceTaskExecutor(
            ApplicationContext applicationContext,
            @Value("${flow.task.call-service.allow-list:}") String allowList) {
        this.applicationContext = applicationContext;
        this.allowListPrefixes = allowList == null || allowList.isBlank()
                ? List.of()
                : Arrays.stream(allowList.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        if (this.allowListPrefixes.isEmpty()) {
            throw new IllegalStateException(
                    "flow.task.call-service.allow-list must not be empty when CallService is enabled. "
                            + "Set it to a comma-separated list of permitted bean-name prefixes "
                            + "(e.g. flow.task.call-service.allow-list=orderService,quotationService).");
        }
        log.warn("CallService executor enabled; permitted bean-name prefixes: {}", this.allowListPrefixes);
    }

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.CALL_SERVICE;
    }

    @Override
    public String getExecutor() {
        return "CallService";
    }

    @Override
    public String getName() {
        return "Call Service";
    }

    @Override
    public String getDescription() {
        return "Invoke a Spring bean method by name. Supports positional args with optional FQCN argTypes.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        return Map.of(
                "beanName", Map.of("type", "string", "label", "Bean Name", "required", true),
                "methodName", Map.of("type", "string", "label", "Method Name", "required", true),
                "args", Map.of("type", "json", "label", "Arguments"),
                "argTypes", Map.of("type", "stringList", "label", "Argument Types (FQCN, optional)")
        );
    }

    @Override
    public String getIcon() {
        return "terminal";
    }

    @Override
    public int getSortOrder() {
        return 71;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        CallServiceConfig cfg = requireConfig(request, CallServiceConfig.class);
        String beanName = requireResolvedString(cfg.getBeanName(), "beanName", variables);
        String methodName = requireResolvedString(cfg.getMethodName(), "methodName", variables);
        checkAllowed(beanName);

        Object bean;
        try {
            bean = applicationContext.getBean(beanName);
        } catch (Exception e) {
            throw new FlowRuntimeException("CallService: bean '" + beanName + "' not found");
        }

        Object[] args = resolveArgs(cfg.getArgs(), variables);
        Object[] typedArgs = applyArgTypes(args, cfg.getArgTypes());

        Object result;
        try {
            if (typedArgs != null) {
                Class<?>[] paramTypes = parseArgTypes(cfg.getArgTypes());
                result = MethodUtils.invokeMethod(bean, methodName, typedArgs, paramTypes);
            } else {
                result = MethodUtils.invokeMethod(bean, methodName, args);
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
            throw new FlowRuntimeException("CallService '" + beanName + "#" + methodName
                    + "' threw: " + cause.getMessage(), cause);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new FlowRuntimeException("CallService '" + beanName + "#" + methodName
                    + "' invocation failed: " + e.getMessage(), e);
        }

        return Collections.singletonMap("result", result);
    }

    private void checkAllowed(String beanName) {
        boolean matched = allowListPrefixes.stream().anyMatch(beanName::startsWith);
        if (!matched) {
            throw new FlowRuntimeException("CallService: bean '" + beanName
                    + "' is not permitted by flow.task.call-service.allow-list");
        }
    }

    private Object[] resolveArgs(Object rawArgs, Map<String, Object> variables) {
        if (rawArgs == null) {
            return new Object[0];
        }
        if (rawArgs instanceof List<?> list) {
            return list.stream()
                    .map(value -> resolveArgValue(value, variables))
                    .toArray();
        }
        return new Object[]{resolveArgValue(rawArgs, variables)};
    }

    private Object resolveArgValue(Object value, Map<String, Object> variables) {
        if (value instanceof String stringValue) {
            Matcher matcher = EXACT_INTERPOLATION.matcher(stringValue);
            if (matcher.matches()) {
                return ComputeUtils.execute(matcher.group(1), new LinkedHashMap<>(variables));
            }
            if (stringValue.contains("{{")) {
                return ComputeUtils.stringInterpolation(stringValue, new LinkedHashMap<>(variables));
            }
            return stringValue;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> resolveArgValue(item, variables))
                    .toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, item) -> resolved.put((String) key, resolveArgValue(item, variables)));
            return resolved;
        }
        return value;
    }

    private Object[] applyArgTypes(Object[] args, Object argTypesRaw) {
        if (argTypesRaw == null) {
            return null;
        }
        if (!(argTypesRaw instanceof List<?> typeList) || typeList.size() != args.length) {
            throw new FlowRuntimeException("CallService: argTypes length must match args length");
        }
        return args;
    }

    private Class<?>[] parseArgTypes(Object argTypesRaw) {
        if (!(argTypesRaw instanceof List<?> typeList)) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[typeList.size()];
        for (int i = 0; i < typeList.size(); i++) {
            String fqcn = String.valueOf(typeList.get(i));
            types[i] = loadClass(fqcn);
        }
        return types;
    }

    private Class<?> loadClass(String fqcn) {
        switch (fqcn) {
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            default:
                try {
                    return Class.forName(fqcn, false, Thread.currentThread().getContextClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new FlowRuntimeException("CallService: argType class not found: " + fqcn);
                }
        }
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        return new LinkedHashMap<>();
    }
}
