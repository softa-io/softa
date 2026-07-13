package io.softa.starter.studio.release.upgrade.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * Pins the studio→runtime export wire contract — the client↔server seam that only meets
 * over HTTP. The narrowing key set must ride in the request BODY (a {@code RuntimeExportFilter}), never
 * the URL, and the full export must POST a null filter (not an empty body). Drives a real
 * {@link RemoteApiClientImpl} over a {@link MockRestServiceServer}.
 */
class RemoteApiClientImplTest {

    private static DesignAppEnv env() {
        DesignAppEnv e = new DesignAppEnv();
        e.setId(1L);
        e.setUpgradeEndpoint("http://runtime.example");
        return e;
    }

    @Test
    @DisplayName("narrowed export POSTs keyColumn/keyValues in the body (not the URL) and returns rows")
    void narrowedExportPutsKeysInBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteApiClientImpl client = new RemoteApiClientImpl(builder.build());

        server.expect(requestTo(Matchers.containsString("/upgrade/runtime/exportRuntimeMetadata")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(queryParam("modelName", "SysField"))
                .andExpect(queryParam("appCode", "demo-app"))
                // The narrowing set is in the body, not a (URL-length-bounded) query param.
                .andExpect(jsonPath("$.keyColumn").value("modelName"))
                .andExpect(jsonPath("$.keyValues[0]").value("Account"))
                .andExpect(jsonPath("$.keyValues[1]").value("Order"))
                .andRespond(withSuccess("{\"code\":200,\"data\":[{\"modelName\":\"Account\"}]}",
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> rows = client.fetchRuntimeMetadata(
                env(), "demo-app", "SysField", "modelName", List.of("Account", "Order"));

        assertEquals(1, rows.size());
        assertEquals("Account", rows.getFirst().get("modelName"));
        server.verify();
    }

    @Test
    @DisplayName("full export POSTs a null filter body (keyColumn/keyValues null), not an empty body")
    void fullExportPostsNullFilter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteApiClientImpl client = new RemoteApiClientImpl(builder.build());

        server.expect(requestTo(Matchers.containsString("exportRuntimeMetadata")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.keyColumn").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.keyValues").value(Matchers.nullValue()))
                // No derived field leaks onto the wire (the DTO is pure data — guards the isFullExport regression).
                .andExpect(jsonPath("$.fullExport").doesNotExist())
                .andRespond(withSuccess("{\"code\":200,\"data\":[]}", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> rows = client.fetchRuntimeMetadata(env(), "demo-app", "SysModel");

        assertEquals(0, rows.size());
        server.verify();
    }

    @Test
    @DisplayName("apply throws on a 200 reply carrying a non-SUCCESS body code (no false convergence)")
    void applyChangesThrowsOnNonSuccessBodyCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteApiClientImpl client = new RemoteApiClientImpl(builder.build());

        // HTTP 200, but the runtime rejected the apply in-band (e.g. appCode handshake failed, or the
        // apply itself failed). If the body's SUCCESS-code assertion regressed, studio would treat this
        // rejected deploy as applied and report false convergence.
        server.expect(requestTo(Matchers.containsString("/upgrade/runtime/applyDesiredAggregates")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(queryParam("appCode", "demo-app"))
                .andRespond(withSuccess("{\"code\":403,\"msg\":\"appCode mismatch\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalArgumentException.class, () ->
                client.applyChanges(env(), "demo-app", new MetadataChangeSet(List.of(), List.of())));
        server.verify();
    }

    @Test
    @DisplayName("checksum fetch throws on a 200 reply carrying a non-SUCCESS body code")
    void fetchChecksumsThrowsOnNonSuccessBodyCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteApiClientImpl client = new RemoteApiClientImpl(builder.build());

        // A rejected checksum gate must not be mistaken for a populated (in-sync) checksum set.
        server.expect(requestTo(Matchers.containsString("/upgrade/runtime/exportRuntimeChecksums")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(queryParam("appCode", "demo-app"))
                .andRespond(withSuccess("{\"code\":401,\"msg\":\"bad signature\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalArgumentException.class, () -> client.fetchRuntimeChecksums(env(), "demo-app"));
        server.verify();
    }

    @Test
    @DisplayName("an HTTP error status (401 bad signature) surfaces as an annotated failure, not an opaque RestClient exception")
    void applyChangesThrowsAnnotatedOnHttpErrorStatus() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteApiClientImpl client = new RemoteApiClientImpl(builder.build());

        // A rejected signature makes the runtime reply HTTP 401 — the common auth-failure mode. Without the
        // onStatus handler this rethrows as an opaque HttpClientErrorException (no URI/body); the handler
        // instead throws the framework IllegalArgumentException carrying URI + status + body.
        server.expect(requestTo(Matchers.containsString("/upgrade/runtime/applyDesiredAggregates")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":401,\"msg\":\"bad signature\"}"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                client.applyChanges(env(), "demo-app", new MetadataChangeSet(List.of(), List.of())));
        assertTrue(ex.getMessage().contains("bad signature"),
                "the annotated failure should carry the runtime's error body: " + ex.getMessage());
        server.verify();
    }
}
