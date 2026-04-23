package io.softa.starter.metadata.signature;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

import io.softa.framework.base.exception.SignatureException;
import io.softa.framework.web.signature.Ed25519Signer;
import io.softa.framework.web.signature.SignatureConstant;
import io.softa.framework.web.signature.support.CanonicalRequest;

/**
 * Server-side Ed25519 verification filter for the softa v1 HTTP signing
 * convention (the canonical form + three header names owned by
 * {@code softa-web}).
 * <p>
 * Annotation-scoped: the filter resolves the incoming request to a
 * {@link HandlerMethod} and only enforces verification when the handler
 * (or its declaring class) carries {@link io.softa.framework.web.signature.RequireSignature}. Non-signed
 * endpoints on the same service pass through untouched.
 * <p>
 * The filter authenticates request <em>origin</em> — it proves the request
 * was signed by the configured signer, that the body wasn't tampered with,
 * and that the timestamp is within skew tolerance. It does <em>not</em>
 * prevent replay of a captured signed request inside the skew window;
 * endpoints with side effects should enforce their own idempotency.
 * <p>
 * Verification order is designed to reject cheap failures first — missing
 * headers, clock skew — before paying the cost of an actual Ed25519
 * verification. The body is read once into memory via a small request wrapper
 * so both the verifier and the downstream handler see the same bytes.
 */
@Slf4j
public class SignatureVerificationFilter extends OncePerRequestFilter {

    private final PublicKey publicKey;
    private final List<HandlerMapping> handlerMappings;

    public SignatureVerificationFilter(PublicKey publicKey, List<HandlerMapping> handlerMappings) {
        this.publicKey = publicKey;
        this.handlerMappings = handlerMappings;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws IOException, jakarta.servlet.ServletException {
        // Resolve the handler first so unsigned endpoints within this filter's URL
        // pattern don't pay the body-buffering cost. Only the signed endpoints end
        // up reading the full body into memory for hashing.
        if (!requiresSignature(request)) {
            chain.doFilter(request, response);
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request);
        try {
            verify(cached);
        } catch (SignatureException e) {
            log.warn("Signature verification rejected {} {}: {}",
                    cached.getMethod(), cached.getRequestURI(), e.getMessage());
            response.sendError(HttpStatus.UNAUTHORIZED.value(), e.getMessage());
            return;
        }
        chain.doFilter(cached, response);
    }

    private boolean requiresSignature(HttpServletRequest request) {
        try {
            for (HandlerMapping mapping : handlerMappings) {
                HandlerExecutionChain hc = mapping.getHandler(request);
                if (hc == null) continue;
                Object handler = hc.getHandler();
                if (handler instanceof HandlerMethod hm) {
                    return hm.hasMethodAnnotation(io.softa.framework.web.signature.RequireSignature.class)
                            || hm.getBeanType().isAnnotationPresent(io.softa.framework.web.signature.RequireSignature.class);
                }
            }
        } catch (Exception e) {
            log.debug("Handler resolution failed while checking signature requirement", e);
        }
        return false;
    }

    private void verify(CachedBodyRequest request) {
        String signature = required(request, SignatureConstant.SIGNATURE);
        String timestampStr = required(request, SignatureConstant.TIMESTAMP);
        String nonce = required(request, SignatureConstant.NONCE);

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            throw new SignatureException("Malformed timestamp header");
        }
        long skew = Math.abs(System.currentTimeMillis() - timestamp);
        if (skew > SignatureConstant.CLOCK_SKEW_TOLERANCE.toMillis()) {
            throw new SignatureException("Timestamp outside skew tolerance (" + skew + "ms)");
        }

        byte[] canonical = CanonicalRequest.build(
                request.getMethod(),
                java.net.URI.create(request.getRequestURL()
                        + (request.getQueryString() == null ? "" : "?" + request.getQueryString())),
                request.getBody(), timestamp, nonce);

        byte[] sig;
        try {
            sig = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            throw new SignatureException("Malformed signature encoding");
        }

        if (!Ed25519Signer.verify(publicKey, canonical, sig)) {
            throw new SignatureException("Signature mismatch");
        }
    }

    private String required(HttpServletRequest req, String headerName) {
        String value = req.getHeader(headerName);
        if (value == null || value.isBlank()) {
            throw new SignatureException("Missing required header: " + headerName);
        }
        return value;
    }

    /**
     * Request wrapper that buffers the body eagerly so both the verifier and
     * downstream handlers can read it. Ed25519 signing covers a SHA-256 digest,
     * not the full body, so buffering cost is bounded to a single pass regardless
     * of payload size.
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = StreamUtils.copyToByteArray(request.getInputStream());
        }

        byte[] getBody() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) { /* no-op */ }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** Expose so tests / helpers can peek at the resolved body. */
    @SuppressWarnings("unused")
    private static Optional<byte[]> bodyOf(HttpServletRequest request) {
        return request instanceof CachedBodyRequest cbr ? Optional.of(cbr.getBody()) : Optional.empty();
    }
}
