package io.softa.starter.permission.interceptor;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The permissions that let the current request through the endpoint gate, and the model its path names.
 *
 * <p>A custom endpoint writes its model without any standard endpoint of that model's write: no role
 * holds the write as the data plane counts it, so who is writing falls back to every role the user has.
 * The gate knew all along — the request came in on a permission — and this keeps that answer for the
 * rest of the request.
 *
 * <p>Kept on the request, never on the context: a flow or task that runs on after the request is not
 * admitted by it.
 *
 * @param pathModel   the first segment of the in-app path — {@code Contract} for
 *                    {@code /Contract/upload}
 * @param permissions the permission ids the endpoint is registered under
 */
public record AdmittedRequest(String pathModel, Set<String> permissions) {

    private static final String ATTRIBUTE = AdmittedRequest.class.getName();

    /** Called by the endpoint gate once the caller holds one of {@code permissions}. */
    public static void record(HttpServletRequest req, String uri, Set<String> permissions) {
        req.setAttribute(ATTRIBUTE, new AdmittedRequest(pathModelOf(uri), Set.copyOf(permissions)));
    }

    /** The current request's admission; {@code null} off a request or when the gate admitted none. */
    public static AdmittedRequest current() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra == null) {
            return null;
        }
        return ra.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof AdmittedRequest admitted
                ? admitted : null;
    }

    static String pathModelOf(String uri) {
        if (uri == null || !uri.startsWith("/")) {
            return "";
        }
        int slash = uri.indexOf('/', 1);
        return slash < 0 ? uri.substring(1) : uri.substring(1, slash);
    }
}
