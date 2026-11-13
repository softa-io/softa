package io.softa.framework.web.utils;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credentials in the request details an error log line carries: the method parameters collected by
 * {@code ApiExceptionAspect}, the query string and the cached JSON request body. A sign-in, password change or
 * verification refused for a wrong value would otherwise write the password, one-time code or ticket into the
 * log, and through the log into Sentry, which takes ERROR lines as events and the request's earlier lines as
 * their breadcrumbs.
 * <p>
 * A value is masked by the name it is sent under, matched whole and ignoring case: {@code token} is masked,
 * {@code tokenType} is not. A null or empty value is kept, since it hides nothing and may be why the request
 * was refused.
 */
public final class SecretMasker {

    /** What a masked value is replaced with. */
    public static final String MASK = "***";

    /**
     * The names credentials are sent under, lower-cased: the fields and parameters of the sign-in, password
     * reset, join and {@code /me} flows, the service credentials written through the model APIs (mail servers,
     * data sources, SMS providers, AI models, app environments) and the RPC headers. {@code code} is not among
     * them: it names business codes far more often, so a verification code is masked when sent as
     * {@code verificationCode}.
     */
    private static final Set<String> SECRET_NAMES = Set.of(
            "password", "newpassword", "currentpassword", "oldpassword", "jdbcpassword",
            "verificationcode", "codeverifier", "ticket", "proof", "token", "authtoken", "verificationtoken",
            "secret", "apikey", "apisecret", "privatekey");

    /**
     * A JSON string, followed by the value when the string names a member whose value is a string, a number or a
     * literal. Every string is matched whole, so the scan never starts inside one; a string value cut off at the
     * end of a truncated body still counts.
     */
    private static final Pattern JSON_STRING = Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.)*+)\"(?:\\s*+:\\s*+(\"(?:[^\"\\\\]|\\\\.)*+\"?|[-+.\\deE]++|true|false|null))?");

    /** A query-string parameter that has a value. */
    private static final Pattern QUERY_PARAMETER = Pattern.compile("([^&=]++)=([^&]++)");

    private SecretMasker() {
    }

    /**
     * Whether a value sent under this name is masked.
     *
     * @param name a parameter, query parameter or JSON member name
     * @return true when the name is one credentials are sent under
     */
    public static boolean isSecretName(String name) {
        return name != null && SECRET_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Masks the values of secret members anywhere in a JSON text, in nested objects and arrays too. A text with no
     * secret member, or that is not JSON, comes back as it is.
     *
     * @param json a JSON text, possibly malformed or truncated
     * @return the text with the values of secret members masked
     */
    public static String maskJson(String json) {
        return mask(JSON_STRING, json, "\"" + MASK + "\"");
    }

    /**
     * Masks the values of secret parameters in a query string.
     *
     * @param queryString the query string of a request, without the leading {@code ?}
     * @return the query string with the values of secret parameters masked
     */
    public static String maskQueryString(String queryString) {
        return mask(QUERY_PARAMETER, queryString, MASK);
    }

    /** Replaces group 2 with the replacement wherever group 1 is a secret name and group 2 holds a value. */
    private static String mask(Pattern pattern, String text, String replacement) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder masked = null;
        int copied = 0;
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value == null || "null".equals(value) || "\"\"".equals(value) || !isSecretName(matcher.group(1))) {
                continue;
            }
            if (masked == null) {
                masked = new StringBuilder(text.length());
            }
            masked.append(text, copied, matcher.start(2)).append(replacement);
            copied = matcher.end(2);
        }
        return masked == null ? text : masked.append(text, copied, text.length()).toString();
    }
}
