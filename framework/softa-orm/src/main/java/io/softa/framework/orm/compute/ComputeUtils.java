package io.softa.framework.orm.compute;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.googlecode.aviator.*;
import com.googlecode.aviator.exception.ExpressionSyntaxErrorException;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.ValueType;

/**
 * Formula calculation tool class, set to safe sandbox mode.
 */
public abstract class ComputeUtils {
    private static final Pattern INTERPOLATION_PATTERN = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}");

    private ComputeUtils() {}

    private static final AviatorEvaluatorInstance ENGINE = newSandboxedInstance();

    /**
     * Creates a fresh {@link AviatorEvaluatorInstance} configured with the framework's
     * safety baseline (decimal-only DECIMAL64 arithmetic, compilation cache, property
     * syntax sugar, safe sandbox feature set, loop cap, class whitelist and the standard
     * date/string function imports) — the exact configuration of the shared engine
     * behind this utility class.
     *
     * <p>Domain modules (e.g. payroll) that need their OWN evaluator instance — typically
     * to register domain functions via {@code addFunction} without polluting the shared
     * framework engine — should build it through this factory so the safety baseline is
     * inherited (and follows framework upgrades) instead of being copied.
     *
     * @return a new sandbox-configured evaluator instance, owned by the caller
     */
    public static AviatorEvaluatorInstance newSandboxedInstance() {
        AviatorEvaluatorInstance engine = AviatorEvaluator.newInstance();
        configureSandbox(engine);
        return engine;
    }

    /** Applies the framework safety baseline to the given engine instance. */
    private static void configureSandbox(AviatorEvaluatorInstance engine) {
        // Enable compilation cache mode by default
        engine.setCachedExpressionByDefault(true);
        // Forbid calling methods through reflection, equivalent to closing custom functions
        engine.setFunctionMissing(null);
        // Enable variable syntax sugar, access object data through a.b.c cascade
        engine.setOption(Options.ENABLE_PROPERTY_SYNTAX_SUGAR, true);
        // Forbid modifying env to avoid polluting the original data
        engine.setOption(Options.USE_USER_ENV_AS_TOP_ENV_DIRECTLY, false);
        // Auto convert float numbers and integer numbers to Decimal.
        engine.setOption(Options.ALWAYS_PARSE_FLOATING_POINT_NUMBER_INTO_DECIMAL, true);
        engine.setOption(Options.ALWAYS_PARSE_INTEGRAL_NUMBER_INTO_DECIMAL, true);
        /*
          Keep 16 decimal places of precision during the calculation process,
          note that DECIMAL64 uses HALF_EVEN, which is the banker's rounding method.
         */
        engine.setOption(Options.MATH_CONTEXT, MathContext.DECIMAL64);
        // Forbid infinite loop, set the maximum number of loops to 100,000
        engine.setOption(Options.MAX_LOOP_COUNT, 100000);
        /*
          Safe sandbox mode settings:
          Enable features: Assignment, Return, If, Loop, Braces code block, Lambda function
          Disable features: custom function, internal system variables, Module, Exception handling, New, Import, Static field, Static method
         */
        engine.setOption(Options.FEATURE_SET, Feature.asSet(
                Feature.Assignment,
                Feature.Return,
                Feature.If,
                Feature.ForLoop,
                Feature.WhileLoop,
                Feature.Let,
                Feature.LexicalScope,
                Feature.Lambda));
        // Forbid instantiating class objects in expressions
        HashSet<Object> enableClasses = new HashSet<>();
        enableClasses.add(ChronoUnit.class);
        engine.setOption(Options.ALLOWED_CLASS_SET, enableClasses);
        // Import static methods of LocalDate, LocalDateTime, DateTimeFormatter, StringTools, Toolkit
        try {
            engine.importFunctions(LocalDate.class);
            engine.importFunctions(LocalDateTime.class);
            engine.importFunctions(DateTimeFormatter.class);
            engine.importFunctions(ChronoUnit.class);
            engine.importFunctions(StringTools.class);
            engine.importFunctions(CronUtils.class);
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * Compile the expression and return the expression object
     * @param expression expression
     * @return expression object
     */
    private static Expression compile(String expression) {
        try {
            return ENGINE.compile(expression);
        } catch (ExpressionSyntaxErrorException e) {
            throw new ValidationException(e.getMessage(), e);
        }
    }

    /**
     * Calculate the expression without parameters and return the calculation result
     * @param expression expression
     * @return calculation result
     */
    public static Object execute(String expression) {
        return execute(expression, null);
    }

    /**
     * Execute the expression with environment variables and return the calculation result.
     * @param expression expression
     * @param env environment variables
     * @return calculation result
     */
    public static Object execute(String expression, Map<String, Object> env) {
        env = formatEnvValues(env);
        return compile(expression).execute(env);
    }

    /**
     * Execute Boolean calculation and return a boolean result
     * @param expression expression
     * @param env environment variables
     * @return boolean calculation result
     */
    public static boolean executeBoolean(String expression, Map<String, Object> env) {
        return Boolean.TRUE.equals(execute(expression, env));
    }

    /**
     * Execute String calculation and return a string result
     * @param expression expression
     * @param env environment variables
     * @return string calculation result
     */
    public static String executeString(String expression, Map<String, Object> env) {
        Object result = execute(expression, env);
        return result == null ? "" : result.toString();
    }

    /**
     * Format environment variable values, convert ObjectNode and ArrayNode to Map and List respectively,
     * and add ChronoUnitUtils.CHRONO_UNIT_ENV to the environment variables.
     *
     * @param env environment variables
     */
    private static Map<String, Object> formatEnvValues(Map<String, Object> env) {
        if (env == null) {
            return ChronoUnitUtils.CHRONO_UNIT_ENV;
        }
        for (Map.Entry<String, Object> entry : env.entrySet()) {
            if (entry.getValue() instanceof JsonNode jsonNode) {
                entry.setValue(JsonUtils.jsonNodeToObject(jsonNode));
            }
        }
        env.putAll(ChronoUnitUtils.CHRONO_UNIT_ENV);
        return env;
    }

    /**
     * Format the calculation result.
     * For number fields, convert the BigDecimal calculation result to the corresponding field type
     * @param result calculation result
     * @param scale number precision
     * @param valueType value type of the result
     * @return formatted calculation result
     */
    private static Object formatResultValue(Object result, Integer scale, ValueType valueType) {
        if (result == null) {
            return valueType == null ? null : valueType.getDefaultValue();
        } else if (valueType.getFieldType().getJavaType().isInstance(result)) {
            return result;
        } else if (ValueType.LONG.equals(valueType) && result instanceof BigDecimal bigDecimal) {
            return bigDecimal.longValue();
        } else if (ValueType.DOUBLE.equals(valueType) && result instanceof BigDecimal bigDecimal) {
            return bigDecimal.setScale(scale, RoundingMode.HALF_UP).doubleValue();
        } else if (ValueType.INTEGER.equals(valueType) && result instanceof BigDecimal bigDecimal) {
            return bigDecimal.intValue();
        } else if (ValueType.INTEGER.equals(valueType) && result instanceof Long longValue) {
            return longValue.intValue();
        } else if (ValueType.BIG_DECIMAL.equals(valueType) && !(result instanceof BigDecimal)) {
            return new BigDecimal(result.toString()).setScale(scale, RoundingMode.HALF_UP);
        } else {
            return result;
        }
    }

    /**
     * Calculate the expression and return the result value of the specified number precision and data type
     * @param expression calculation expression
     * @param env environment variables
     * @param scale number precision
     * @param valueType value type of the result
     * @return calculation result
     */
    public static Object execute(String expression, Map<String, Object> env, Integer scale, ValueType valueType) {
        Object result = execute(expression, env);
        return formatResultValue(result, scale, valueType);
    }

    /**
     * Return the calculation result of the specified field type fieldType, using the default value for number precision
     * @param expression calculation expression
     * @param env environment variables
     * @param valueType value type of the result
     * @return calculation result
     */
    public static Object execute(String expression, Map<String, Object> env, @NotNull  ValueType valueType) {
        return execute(expression, env, BaseConstant.DEFAULT_SCALE, valueType);
    }

    /**
     * Return the calculation result of the specified field type fieldType, using the specified number precision
     * @param expression calculation expression
     * @param env environment variables
     * @param scale number precision
     * @param fieldType field type
     * @return calculation result
     */
    public static Object execute(String expression, Map<String, Object> env, Integer scale, FieldType fieldType) {
        ValueType valueType = ValueType.of(fieldType);
        return execute(expression, env, scale, valueType);
    }

    /**
     * String interpolation calculation.
     * Return original value if it is not an interpolation expression.
     * @param expression expression, e.g.: hello, {{ name }}
     * @param env environment variables
     * @return interpolation calculation result
     */
    public static String stringInterpolation(String expression, Map<String, Object> env) {
        if (expression == null || expression.isEmpty()) {
            return "";
        } else if (!expression.contains("{{")) {
            return expression;
        }
        Matcher matcher = INTERPOLATION_PATTERN.matcher(expression);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = execute(matcher.group(1).trim(), env);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Get the top-level variable list of the expression. Framework-injected env namespaces
     * (currently {@code ChronoUnit}, see {@link ChronoUnitUtils#CHRONO_UNIT_ENV}) are filtered
     * out so that callers like metadata field-validation only see real model-field references.
     *
     * @param expression expression
     * @return top-level variable name list
     */
    public static List<String> getVariables(String expression) {
        List<String> variables = new ArrayList<>(compile(expression).getVariableNames());
        variables.removeAll(ChronoUnitUtils.CHRONO_UNIT_ENV.keySet());
        return variables;
    }

    /**
     * Validate the expression syntax
     * @param expression expression
     * @return true if the expression syntax is valid, otherwise false
     */
    public static boolean validateExpression(String expression) {
        try {
            ENGINE.compile(expression);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
