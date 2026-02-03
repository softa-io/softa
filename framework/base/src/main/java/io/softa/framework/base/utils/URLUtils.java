package io.softa.framework.base.utils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * URL utility class
 */
public class URLUtils {

    private static final PathPatternParser PARSER = new PathPatternParser();

    /**
     * Concatenate multiple URL fragments into a complete URI without parameters
     */
    public static String buildUrl(String... clauses) {
        if (clauses.length == 0) {
            return "";
        }
        String uri = String.join("/", clauses);
        // Remove redundant slashes
        return uri.replaceAll("(?<!:)//+", "/");
    }

    /**
     * Add parameters to the URL.
     * Compatible with URL already containing parameters.
     */
    public static String addParamsToUrl(String url, Map<String, Object> parameters) {
        if (StringUtils.isNotBlank(url) && parameters != null && !parameters.isEmpty()) {
            var queryParams = parameters.entrySet().stream()
                    .map(entry -> encodeParam(entry.getKey()) + "=" + encodeParam(entry.getValue().toString()))
                    .collect(Collectors.joining("&"));
            if (url.endsWith("?")) {
                return url + queryParams;
            } else if (url.contains("?")) {
                return url + "&" + queryParams;
            } else {
                return url + "?" + queryParams;
            }
        }
        return url;
    }

    /**
     * Encodes a query parameter using UTF-8.
     */
    public static String encodeParam(String param) {
        return URLEncoder.encode(param, StandardCharsets.UTF_8);
    }

    /**
     * Check if the URI matches the pattern
     * @param pattern pattern
     * @param uri URI
     * @return true if the URI matches the pattern
     */
    public static boolean matchUri(String pattern, String uri) {
        // Parse pattern once per call; use PathPattern for efficient matching.
        PathPattern pathPattern = PARSER.parse(pattern);
        return pathPattern.matches(PathContainer.parsePath(uri));
    }

    /**
     * Check if the URI matches any of the patterns
     * @param patterns patterns
     * @param uri URI
     * @return true if the URI matches any of the patterns
     */
    public static boolean matchUri(List<String> patterns, String uri) {
        for (String pattern : patterns) {
            if (matchUri(pattern, uri)) {
                return true;
            }
        }
        return false;
    }
}
