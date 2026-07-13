package io.softa.starter.flow.runtime.task.builtin;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebHookTaskExecutor} — builtin ServiceTask executor that calls an
 * external HTTP endpoint via the resilient {@code flowWebhookRestClient}.
 */
class WebHookTaskExecutorTest {

    /**
     * Wires up the full {@link RestClient} fluent chain
     * ({@code method → uri → contentType → headers → body → retrieve → toEntity})
     * so that {@code toEntity(Map.class)} returns the supplied response entity.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private RestClient mockRestClient(ResponseEntity<Map> responseEntity) {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.method(any(HttpMethod.class))).thenReturn(uriSpec);
        when(uriSpec.uri(any(URI.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.headers(any())).thenReturn(bodySpec);
        when(bodySpec.body((Object) any())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(Map.class)).thenReturn(responseEntity);
        return restClient;
    }

    @Test
    void getSupportedNodeTypeIsCallWebhook() {
        WebHookTaskExecutor executor = new WebHookTaskExecutor(mock(RestClient.class));
        assertEquals(FlowNodeType.CALL_WEBHOOK, executor.getSupportedNodeType());
        assertEquals("WebHook", executor.getExecutor());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void invokesEndpointAndReturnsStatusAndBody() {
        ResponseEntity<Map> responseEntity = ResponseEntity.ok(Map.of("ok", true));
        RestClient restClient = mockRestClient(responseEntity);
        WebHookTaskExecutor executor = new WebHookTaskExecutor(restClient);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_WEBHOOK)
                .input(Map.of(
                        "url", "https://api.example.com/callback",
                        "method", "POST",
                        "body", Map.of("status", "approved")))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(200, result.get("statusCode"));
        assertEquals(Map.of("ok", true), result.get("body"));
        verify(restClient).method(HttpMethod.POST);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void usesDeclaredHttpMethodCaseInsensitively() {
        RestClient restClient = mockRestClient(ResponseEntity.ok(Map.of()));
        WebHookTaskExecutor executor = new WebHookTaskExecutor(restClient);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_WEBHOOK)
                .input(Map.of(
                        "url", "https://api.example.com/resource",
                        "method", "get"))
                .build();

        executor.execute(request, Map.of());

        verify(restClient).method(HttpMethod.GET);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void interpolatesUrlPlaceholdersFromVariables() {
        RestClient restClient = mockRestClient(ResponseEntity.ok(Map.of()));
        RestClient.RequestBodyUriSpec uriSpec =
                (RestClient.RequestBodyUriSpec) restClient.method(HttpMethod.POST);
        WebHookTaskExecutor executor = new WebHookTaskExecutor(restClient);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_WEBHOOK)
                .input(Map.of(
                        "url", "https://api.example.com/orders/{{ orderId }}",
                        "method", "POST"))
                .build();

        executor.execute(request, Map.of("orderId", "O-42"));

        verify(uriSpec).uri(URI.create("https://api.example.com/orders/O-42"));
    }

    @Test
    void missingUrlThrows() {
        WebHookTaskExecutor executor = new WebHookTaskExecutor(mock(RestClient.class));

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_WEBHOOK)
                .input(Map.of("method", "POST"))
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("input.url"));
    }

    @Test
    void blankUrlThrows() {
        WebHookTaskExecutor executor = new WebHookTaskExecutor(mock(RestClient.class));

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_WEBHOOK)
                .input(Map.of("url", "   "))
                .build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
    }

    @Test
    void rejectsNonHttpSchemes() {
        WebHookTaskExecutor executor = new WebHookTaskExecutor(mock(RestClient.class));

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_WEBHOOK)
                .input(Map.of("url", "file:///etc/passwd"))
                .build();

        assertThrows(FlowRuntimeException.class, () -> executor.execute(request, Map.of()));
    }

    @Test
    void rejectsLocalOrPrivateAddresses() {
        WebHookTaskExecutor executor = new WebHookTaskExecutor(mock(RestClient.class));

        TaskExecutionRequest localhost = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_WEBHOOK)
                .input(Map.of("url", "http://localhost/callback"))
                .build();
        TaskExecutionRequest privateIp = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_WEBHOOK)
                .input(Map.of("url", "http://10.0.0.1/callback"))
                .build();

        assertThrows(FlowRuntimeException.class, () -> executor.execute(localhost, Map.of()));
        assertThrows(FlowRuntimeException.class, () -> executor.execute(privateIp, Map.of()));
    }
}
