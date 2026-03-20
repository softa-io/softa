package io.softa.framework.base.placeholder;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringSubstitutor;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;

/**
 * Placeholder Utils
 */
public class PlaceholderUtils {
    private static final Pattern PLACEHOLDER_VARIABLE_PATTERN = Pattern.compile("^[a-zA-Z_]+(?:\\.[a-zA-Z0-9_]+)*$");


    /**
     * Determine whether the string is an expression.
     *
     * @param str string
     * @return whether it is a calculation expression
     */
    public static boolean isExpression(String str) {
        PlaceholderToken token = parsePlaceholder(str);
        return token != null && PlaceholderKind.EXPRESSION.equals(token.getKind());
    }

    /**
     * Determine whether the string is a variable.
     *
     * @param str string
     * @return whether it is a variable parameter
     */
    public static boolean isVariable(String str) {
        PlaceholderToken token = parsePlaceholder(str);
        return token != null && PlaceholderKind.VARIABLE.equals(token.getKind());
    }

    /**
     * Determine whether the string is a reserved field.
     *
     * @param str string
     * @return whether it is a reserved field
     */
    public static boolean isReservedField(String str) {
        PlaceholderToken token = parsePlaceholder(str);
        return token != null && PlaceholderKind.RESERVED_FIELD.equals(token.getKind());
    }

    /**
     * Extract the placeholder variable list in the text.
     * Such as extracting TriggerParams.id from {{ TriggerParams.id }}
     * @param text text
     * @return placeholder variable list
     */
    public static List<String> extractVariables(String text) {
        List<String> variables = new ArrayList<>();
        // Escape the delimiter
        String escapedPrefixDelimiter = Pattern.quote(StringConstant.PLACEHOLDER_PREFIX);
        String escapedSuffixDelimiter = Pattern.quote(StringConstant.PLACEHOLDER_SUFFIX);
        String regex = escapedPrefixDelimiter + "(.+?)" + escapedSuffixDelimiter;
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        // Find all the placeholders
        while (matcher.find()) {
            variables.add(matcher.group(1).trim());
        }
        return variables;
    }

    /**
     * Parse the placeholder variable from the string and return a PlaceholderToken object
     * that encapsulates the type and content of the placeholder.
     */
    public static @Nullable PlaceholderToken parsePlaceholder(String str) {
        if (str == null
                || !str.startsWith(StringConstant.PLACEHOLDER_PREFIX)
                || !str.endsWith(StringConstant.PLACEHOLDER_SUFFIX)) {
            return null;
        }
        String content = str.substring(
                StringConstant.PLACEHOLDER_PREFIX.length(),
                str.length() - StringConstant.PLACEHOLDER_SUFFIX.length()
        ).trim();
        if (content.isEmpty()) {
            return null;
        }
        if (content.startsWith(StringConstant.RESERVED_REFERENCE_PREFIX)) {
            String stripped = content.substring(StringConstant.RESERVED_REFERENCE_PREFIX.length()).trim();
            if (PLACEHOLDER_VARIABLE_PATTERN.matcher(stripped).matches()) {
                return new PlaceholderToken(PlaceholderKind.RESERVED_FIELD, stripped);
            } else {
                throw new IllegalArgumentException("The content of the reserved field placeholder must be a valid variable name: {0}", content);
            }
        } else if (PLACEHOLDER_VARIABLE_PATTERN.matcher(content).matches()) {
            return new PlaceholderToken(PlaceholderKind.VARIABLE, content);
        } else {
            return new PlaceholderToken(PlaceholderKind.EXPRESSION, content);
        }
    }

    /**
     * Extract the value of a variable from the environment context using an already-parsed PlaceholderToken.
     * This avoids redundant parsing when the caller has already called {@link #parsePlaceholder(String)}.
     *
     * @param token  the parsed placeholder token
     * @param env    the environment context
     * @return the resolved variable value
     */
    public static Object extractVariable(PlaceholderToken token, Map<String, Object> env) {
        Assert.notNull(token, "Placeholder token cannot be null.");
        String variable = token.getContent();
        Assert.notBlank(variable, "Variable parameter {0} cannot be empty.", variable);
        String[] variableNames = variable.split("\\.");
        Assert.isTrue(env.containsKey(variableNames[0]),
                "Variable parameter {0} does not exist in the environment context!", variable);
        Object value = env.get(variableNames[0]);
        for (int i = 1; i < variableNames.length; i++) {
            if (value == null) {
                return null;
            }
            if (value instanceof Map<?, ?> valueMap) {
                value = valueMap.get(variableNames[i]);
                continue;
            }
            throw new IllegalArgumentException(
                    "The variable parameter {0} cannot retrieve its value from the environment context: {1}!",
                    variable, value);
        }
        return value;
    }

    /**
     * Replace a single placeholder variable in the text
     *
     * @param text text
     * @param placeholder placeholder variable
     * @param value value
     * @return replaced text
     */
    public static String replacePlaceholder(String text, String placeholder, String value) {
        String regex = Pattern.quote(StringConstant.PLACEHOLDER_PREFIX)
                + Pattern.quote(placeholder)
                + Pattern.quote(StringConstant.PLACEHOLDER_SUFFIX);
        return text.replaceAll(regex, value);
    }

    /**
     * Replace multiple placeholder variables in the text
     * @param text text
     * @param placeholderMap placeholder variable map
     * @return replaced text
     */
    public static String replacePlaceholders(String text, Map<String, Object> placeholderMap) {
        StringSubstitutor sub = new StringSubstitutor(
                placeholderMap,
                StringConstant.PLACEHOLDER_PREFIX,
                StringConstant.PLACEHOLDER_SUFFIX
        );
        // Disable recursive and nested placeholders
        sub.setEnableSubstitutionInVariables(false);
        return sub.replace(text);
    }

    private static String stripReservedPrefix(String content) {
        return content.substring(StringConstant.RESERVED_REFERENCE_PREFIX.length()).trim();
    }
}
