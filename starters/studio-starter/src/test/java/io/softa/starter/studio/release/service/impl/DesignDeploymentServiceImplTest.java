package io.softa.starter.studio.release.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.metadata.dto.MetadataUpgradeCallback;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.entity.DesignDeployment;
import io.softa.starter.studio.release.enums.DesignAppEnvStatus;
import io.softa.starter.studio.release.enums.DesignAppEnvType;
import io.softa.starter.studio.release.enums.DesignDeploymentStatus;
import io.softa.starter.studio.release.service.DesignAppEnvService;
import io.softa.starter.studio.release.service.DesignAppVersionService;
import io.softa.starter.studio.release.service.DesignDeploymentVersionService;
import io.softa.starter.studio.release.service.DesignWorkItemService;
import io.softa.starter.studio.release.upgrade.DeploymentExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DesignDeploymentServiceImplTest {

    @Test
    void successCallbackAdvancesEnvVersionAndReleasesLock() {
        CallbackScenario s = new CallbackScenario();
        s.deployment.setDeployStatus(DesignDeploymentStatus.DEPLOYING);
        s.deployment.setCallbackTokenExpireAt(LocalDateTime.now().plusHours(1));

        MetadataUpgradeCallback payload = new MetadataUpgradeCallback();
        payload.setStatus("SUCCESS");
        payload.setDurationMillis(1200L);

        s.service.handleUpgradeCallback(s.token, payload);

        assertEquals(DesignDeploymentStatus.SUCCESS, s.deployment.getDeployStatus());
        assertNotNull(s.deployment.getCallbackReceivedAt());
        assertNotNull(s.deployment.getFinishedTime());
        assertEquals(1.2, s.deployment.getDeployDuration());
        // currentVersionId must advance to the target version.
        assertEquals(s.targetVersionId, s.targetEnv.getCurrentVersionId());
        // Env mutex is released back to STABLE.
        verify(s.appEnvService).updateByFilter(any(), argThat(env ->
                env.getEnvStatus() == DesignAppEnvStatus.STABLE));
    }

    @Test
    void failureCallbackRecordsErrorAndReleasesLockWithoutAdvancing() {
        CallbackScenario s = new CallbackScenario();
        s.deployment.setDeployStatus(DesignDeploymentStatus.DEPLOYING);
        s.targetEnv.setCurrentVersionId(1L);

        MetadataUpgradeCallback payload = new MetadataUpgradeCallback();
        payload.setStatus("FAILURE");
        payload.setErrorMessage("DDL apply failed");
        payload.setDurationMillis(400L);

        s.service.handleUpgradeCallback(s.token, payload);

        assertEquals(DesignDeploymentStatus.FAILURE, s.deployment.getDeployStatus());
        assertEquals("DDL apply failed", s.deployment.getErrorMessage());
        // currentVersionId must NOT move on failure.
        assertEquals(1L, s.targetEnv.getCurrentVersionId());
        verify(s.appEnvService).updateByFilter(any(), argThat(env ->
                env.getEnvStatus() == DesignAppEnvStatus.STABLE));
    }

    @Test
    void unknownTokenIsRejected() {
        CallbackScenario s = new CallbackScenario();
        doReturn(List.of()).when(s.service).searchList(any(FlexQuery.class));
        MetadataUpgradeCallback payload = new MetadataUpgradeCallback();
        payload.setStatus("SUCCESS");
        assertThrows(IllegalArgumentException.class,
                () -> s.service.handleUpgradeCallback("no-such-token", payload));
    }

    @Test
    void expiredTokenIsRejected() {
        CallbackScenario s = new CallbackScenario();
        s.deployment.setDeployStatus(DesignDeploymentStatus.DEPLOYING);
        s.deployment.setCallbackTokenExpireAt(LocalDateTime.now().minusMinutes(1));
        MetadataUpgradeCallback payload = new MetadataUpgradeCallback();
        payload.setStatus("SUCCESS");
        assertThrows(IllegalArgumentException.class,
                () -> s.service.handleUpgradeCallback(s.token, payload));
    }

    @Test
    void duplicateCallbackIsRejectedByReceivedAtGuard() {
        CallbackScenario s = new CallbackScenario();
        s.deployment.setDeployStatus(DesignDeploymentStatus.DEPLOYING);
        s.deployment.setCallbackReceivedAt(LocalDateTime.now().minusSeconds(10));
        MetadataUpgradeCallback payload = new MetadataUpgradeCallback();
        payload.setStatus("SUCCESS");
        assertThrows(IllegalArgumentException.class,
                () -> s.service.handleUpgradeCallback(s.token, payload));
    }

    @Test
    void callbackRejectedWhenDeploymentNoLongerDeploying() {
        CallbackScenario s = new CallbackScenario();
        s.deployment.setDeployStatus(DesignDeploymentStatus.SUCCESS);
        MetadataUpgradeCallback payload = new MetadataUpgradeCallback();
        payload.setStatus("SUCCESS");
        assertThrows(IllegalArgumentException.class,
                () -> s.service.handleUpgradeCallback(s.token, payload));
    }

    /**
     * Fixture that wires up a {@link DesignDeploymentServiceImpl} spy with just enough
     * collaborators to exercise {@link DesignDeploymentServiceImpl#handleUpgradeCallback}.
     * Each test mutates the pre-built {@code deployment} / {@code targetEnv} to set up
     * the scenario it wants to validate.
     */
    private static final class CallbackScenario {
        final DesignDeploymentServiceImpl service = Mockito.spy(new DesignDeploymentServiceImpl());
        final DesignAppEnvService appEnvService = mock(DesignAppEnvService.class);
        final DesignAppVersionService appVersionService = mock(DesignAppVersionService.class);
        final DesignDeploymentVersionService deploymentVersionService = mock(DesignDeploymentVersionService.class);
        final DesignWorkItemService workItemService = mock(DesignWorkItemService.class);
        final ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);

        final String token = "token-123";
        final Long deploymentId = 99L;
        final Long envId = 200L;
        final Long targetVersionId = 500L;

        final DesignDeployment deployment = new DesignDeployment();
        final DesignAppEnv targetEnv = new DesignAppEnv();

        CallbackScenario() {
            ReflectionTestUtils.setField(service, "deploymentExecutor", mock(DeploymentExecutor.class));
            ReflectionTestUtils.setField(service, "appEnvService", appEnvService);
            ReflectionTestUtils.setField(service, "appVersionService", appVersionService);
            ReflectionTestUtils.setField(service, "deploymentVersionService", deploymentVersionService);
            ReflectionTestUtils.setField(service, "workItemService", workItemService);
            ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
            doReturn(true).when(service).updateOne(any(DesignDeployment.class));
            when(appEnvService.updateOne(any(DesignAppEnv.class))).thenReturn(true);

            deployment.setId(deploymentId);
            deployment.setEnvId(envId);
            deployment.setTargetVersionId(targetVersionId);
            deployment.setCallbackToken(token);
            deployment.setMergedContent(JsonUtils.objectToJsonNode(List.of()));

            targetEnv.setId(envId);
            targetEnv.setEnvType(DesignAppEnvType.TEST);

            // Spy method overrides must use doReturn(...).when(...) — when(...).thenReturn(...)
            // invokes the real method first, which here delegates to an un-wired modelService.
            doReturn(List.of(deployment)).when(service).searchList(any(FlexQuery.class));
            when(appEnvService.getById(envId)).thenReturn(Optional.of(targetEnv));
        }
    }
}
