package io.softa.starter.studio.release.signing;

import java.io.IOException;
import java.security.PrivateKey;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.SignatureException;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.signature.Ed25519Keys;
import io.softa.framework.web.signature.Ed25519Signer;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * Studio-side signing interceptor. Each {@code DesignAppEnv} row owns its own
 * Ed25519 keypair; the caller tags an outbound request with
 * {@link #ENV_ID_HEADER} and this interceptor loads the env's private key,
 * applies the framework signing protocol, and strips the internal marker
 * before the request leaves.
 * <p>
 * The marker is restored in a finally block so an outer resilience retry can
 * re-enter the chain and trigger a fresh timestamp + nonce — reusing a
 * signature across retries would double-book against the verifier's clock-skew
 * window.
 */
@Slf4j
@Component
public class DesignAppEnvSigningInterceptor implements ClientHttpRequestInterceptor {

    /**
     * Internal header carrying the env id whose private key should sign the
     * request. Stripped by this interceptor; never reaches the wire.
     */
    public static final String ENV_ID_HEADER = "X-Studio-Signing-Env-Id";

    private static final String MODEL_NAME = DesignAppEnv.class.getSimpleName();
    private static final List<String> KEY_FIELDS = List.of("privateKey");

    private final ModelService<Long> modelService;

    public DesignAppEnvSigningInterceptor(ModelService<Long> modelService) {
        this.modelService = modelService;
    }

    @Override
    public @NonNull ClientHttpResponse intercept(HttpRequest request, byte @NonNull [] body, @NonNull ClientHttpRequestExecution execution)
            throws IOException {
        HttpHeaders headers = request.getHeaders();
        String envIdStr = headers.getFirst(ENV_ID_HEADER);
        if (envIdStr == null || envIdStr.isBlank()) {
            return execution.execute(request, body);
        }
        headers.remove(ENV_ID_HEADER);
        try {
            Ed25519Signer.attachHttpRequest(request, body, loadPrivateKey(parseEnvId(envIdStr)));
            return execution.execute(request, body);
        } finally {
            headers.set(ENV_ID_HEADER, envIdStr);
        }
    }

    private Long parseEnvId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new SignatureException("Malformed signing env id: " + raw);
        }
    }

    private PrivateKey loadPrivateKey(Long envId) {
        Map<String, Object> env = modelService.getById(MODEL_NAME, envId, KEY_FIELDS)
                .orElseThrow(() -> new SignatureException("Signing env not found: id=" + envId));
        String encoded = (String) env.get("privateKey");
        if (encoded == null || encoded.isBlank()) {
            throw new SignatureException("Env " + envId + " has no issued signing key — call issueKey first");
        }
        return Ed25519Keys.decodePrivateKey(encoded);
    }
}
