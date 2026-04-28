package io.softa.starter.studio.release.signing;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import io.softa.framework.base.exception.SignatureException;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.signature.Ed25519Keys;
import io.softa.framework.web.signature.SignatureConstant;
import io.softa.framework.web.signature.support.CanonicalRequest;
import io.softa.starter.studio.release.entity.DesignAppEnv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesignAppEnvSigningInterceptorTest {

    @Test
    @SuppressWarnings("unchecked")
    void taggedRequestIsSignedWithEnvKeyAndMarkerIsStrippedFromWire() throws Exception {
        KeyPair kp = Ed25519Keys.generate();
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.getById(DesignAppEnv.class.getSimpleName(), 7L, List.of("privateKey")))
                .thenReturn(Optional.of(Map.of("privateKey", Ed25519Keys.encodePrivateKey(kp.getPrivate()))));
        DesignAppEnvSigningInterceptor interceptor = new DesignAppEnvSigningInterceptor(modelService);

        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("https://runtime.example/upgrade/upgradeMetadata"));
        request.getHeaders().set(DesignAppEnvSigningInterceptor.ENV_ID_HEADER, "7");
        byte[] body = "{\"ping\":1}".getBytes(StandardCharsets.UTF_8);

        CapturingExecution exec = new CapturingExecution();
        interceptor.intercept(request, body, exec);

        String signature = request.getHeaders().getFirst(SignatureConstant.SIGNATURE);
        String timestamp = request.getHeaders().getFirst(SignatureConstant.TIMESTAMP);
        String nonce = request.getHeaders().getFirst(SignatureConstant.NONCE);
        assertNotNull(signature);
        assertNotNull(timestamp);
        assertNotNull(nonce);
        // Marker must not leak onto the wire, but must be restored on the request so an
        // outer retry can re-enter and re-sign with a fresh timestamp + nonce.
        assertNull(exec.sentHeaderValue(DesignAppEnvSigningInterceptor.ENV_ID_HEADER));
        assertEquals("7", request.getHeaders().getFirst(DesignAppEnvSigningInterceptor.ENV_ID_HEADER));

        byte[] canonical = CanonicalRequest.build("POST", request.getURI(), body,
                Long.parseLong(timestamp), nonce);
        assertTrue(verify(kp.getPublic(), canonical, Base64.getUrlDecoder().decode(signature)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void untaggedRequestPassesThroughUnsigned() throws Exception {
        ModelService<Long> modelService = mock(ModelService.class);
        DesignAppEnvSigningInterceptor interceptor = new DesignAppEnvSigningInterceptor(modelService);

        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET, URI.create("https://runtime.example/healthz"));

        interceptor.intercept(request, new byte[0], new CapturingExecution());
        assertNull(request.getHeaders().getFirst(SignatureConstant.SIGNATURE));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingEnvRaisesSignatureExceptionRatherThanSendingPlaintext() {
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.getById(DesignAppEnv.class.getSimpleName(), 9L, List.of("privateKey")))
                .thenReturn(Optional.empty());
        DesignAppEnvSigningInterceptor interceptor = new DesignAppEnvSigningInterceptor(modelService);

        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("https://runtime.example/upgrade/upgradeMetadata"));
        request.getHeaders().set(DesignAppEnvSigningInterceptor.ENV_ID_HEADER, "9");

        assertThrows(SignatureException.class,
                () -> interceptor.intercept(request, new byte[0], new CapturingExecution()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void envWithoutIssuedKeyRaisesSignatureException() {
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.getById(DesignAppEnv.class.getSimpleName(), 11L, List.of("privateKey")))
                .thenReturn(Optional.of(Map.of("privateKey", " ")));
        DesignAppEnvSigningInterceptor interceptor = new DesignAppEnvSigningInterceptor(modelService);

        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("https://runtime.example/upgrade/upgradeMetadata"));
        request.getHeaders().set(DesignAppEnvSigningInterceptor.ENV_ID_HEADER, "11");

        assertThrows(SignatureException.class,
                () -> interceptor.intercept(request, new byte[0], new CapturingExecution()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedEnvIdHeaderRaisesSignatureException() {
        ModelService<Long> modelService = mock(ModelService.class);
        DesignAppEnvSigningInterceptor interceptor = new DesignAppEnvSigningInterceptor(modelService);

        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("https://runtime.example/upgrade/upgradeMetadata"));
        request.getHeaders().set(DesignAppEnvSigningInterceptor.ENV_ID_HEADER, "not-a-number");

        assertThrows(SignatureException.class,
                () -> interceptor.intercept(request, new byte[0], new CapturingExecution()));
    }

    private static boolean verify(PublicKey publicKey, byte[] canonical, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance(Ed25519Keys.ALGORITHM);
        verifier.initVerify(publicKey);
        verifier.update(canonical);
        return verifier.verify(signature);
    }

    private static final class CapturingExecution implements org.springframework.http.client.ClientHttpRequestExecution {

        private org.springframework.http.HttpHeaders sentHeaders;

        @Override
        public ClientHttpResponse execute(org.springframework.http.HttpRequest request, byte[] body) {
            this.sentHeaders = new org.springframework.http.HttpHeaders();
            this.sentHeaders.putAll(request.getHeaders());
            return new MockClientHttpResponse(new byte[0], 200);
        }

        String sentHeaderValue(String name) {
            return sentHeaders == null ? null : sentHeaders.getFirst(name);
        }
    }
}
