package io.softa.starter.metadata.controller;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.controller.dto.ResolveCascadedPathsRequest;
import io.softa.starter.metadata.controller.dto.ResolveCascadedPathsResponse;
import io.softa.starter.metadata.service.MetadataService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit unit tests for {@link MetadataController}. Validates the
 * controller's request validation contract and that successful calls
 * delegate to {@link MetadataService} unchanged.
 */
class MetadataControllerTest {

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    private static MetadataController controllerWith(MetadataService service) {
        MetadataController controller = new MetadataController();
        ReflectionTestUtils.setField(controller, "metadataService", service);
        return controller;
    }

    @Test
    void resolveCascadedPathsDelegatesToService() {
        MetadataService service = mock(MetadataService.class);
        ResolveCascadedPathsResponse expected = new ResolveCascadedPathsResponse(List.of(), List.of());
        when(service.resolveCascadedPaths(eq("AppEnv"), eq(List.of("lastDeploymentId.deployStatus"))))
                .thenReturn(expected);

        MetadataController controller = controllerWith(service);
        ResolveCascadedPathsRequest request = new ResolveCascadedPathsRequest();
        request.setRootModel("AppEnv");
        request.setPaths(List.of("lastDeploymentId.deployStatus"));

        ApiResponse<ResolveCascadedPathsResponse> response = controller.resolveCascadedPaths(request);

        assertNotNull(response);
        assertEquals(Integer.valueOf(200), response.getCode());
        assertSame(expected, response.getData());
        verify(service).resolveCascadedPaths("AppEnv", List.of("lastDeploymentId.deployStatus"));
    }

    @Test
    void resolveCascadedPathsRejectsNullBody() {
        MetadataService service = mock(MetadataService.class);
        MetadataController controller = controllerWith(service);

        assertThrows(IllegalArgumentException.class, () -> controller.resolveCascadedPaths(null));
        verifyNoInteractions(service);
    }

    @Test
    void resolveCascadedPathsRejectsBlankRootModel() {
        MetadataService service = mock(MetadataService.class);
        MetadataController controller = controllerWith(service);

        ResolveCascadedPathsRequest req = new ResolveCascadedPathsRequest();
        req.setRootModel("");
        req.setPaths(List.of("foo.bar"));
        assertThrows(IllegalArgumentException.class, () -> controller.resolveCascadedPaths(req));

        ResolveCascadedPathsRequest req2 = new ResolveCascadedPathsRequest();
        req2.setRootModel(null);
        req2.setPaths(List.of("foo.bar"));
        assertThrows(IllegalArgumentException.class, () -> controller.resolveCascadedPaths(req2));

        verifyNoInteractions(service);
    }

    @Test
    void resolveCascadedPathsRejectsEmptyPaths() {
        MetadataService service = mock(MetadataService.class);
        MetadataController controller = controllerWith(service);

        ResolveCascadedPathsRequest req = new ResolveCascadedPathsRequest();
        req.setRootModel("AppEnv");
        req.setPaths(List.of());
        assertThrows(IllegalArgumentException.class, () -> controller.resolveCascadedPaths(req));

        ResolveCascadedPathsRequest req2 = new ResolveCascadedPathsRequest();
        req2.setRootModel("AppEnv");
        req2.setPaths(null);
        assertThrows(IllegalArgumentException.class, () -> controller.resolveCascadedPaths(req2));

        verifyNoInteractions(service);
    }
}
