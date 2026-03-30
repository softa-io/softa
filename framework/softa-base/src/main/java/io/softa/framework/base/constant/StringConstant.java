package io.softa.framework.base.constant;

import java.util.Set;

public interface StringConstant {
    String EMPTY_STRING = "";
    String DISPLAY_NAME_SEPARATOR = " / ";
    String COMMA_SEPARATOR = ",";
    String ALIAS_SEPARATOR = ".";
    String LEFT_SQUARE_BRACKET = "[";
    String RIGHT_SQUARE_BRACKET = "]";

    // The masking character for masking field.
    String MASKING_SYMBOL = "****";

    // Placeholder delimiters for template values.
    String PLACEHOLDER_PREFIX = "{{";
    String PLACEHOLDER_SUFFIX = "}}";

    // The prefix of a reserved field reference inside a placeholder.
    String RESERVED_REFERENCE_PREFIX = "@";

    String UNDERLINE = "_";
    String HYPHEN = "-";
    String SLASH = "/";

    String HTTP_PREFIX = "http://";
    String HTTPS_PREFIX = "https://";

    String NULL_STRING = "null";
    String TRUE_STRING = "true";
    Set<String> EMPTY_STRING_SET = Set.of("''", "\"\"");
}
