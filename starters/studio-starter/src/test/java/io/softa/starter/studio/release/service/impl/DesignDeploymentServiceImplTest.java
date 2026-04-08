package io.softa.starter.studio.release.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.web.dto.MetadataUpgradePackage;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.entity.DesignAppVersion;
import io.softa.starter.studio.release.entity.DesignDeployment;
import io.softa.starter.studio.release.entity.DesignDeploymentVersion;
import io.softa.starter.studio.release.entity.DesignWorkItem;
import io.softa.starter.studio.release.enums.DesignAppEnvType;
import io.softa.starter.studio.release.enums.DesignAppVersionStatus;
import io.softa.starter.studio.release.enums.DesignWorkItemStatus;
import io.softa.starter.studio.release.service.DesignAppEnvService;
import io.softa.starter.studio.release.service.DesignAppVersionService;
import io.softa.starter.studio.release.service.DesignDeploymentVersionService;
import io.softa.starter.studio.release.service.DesignWorkItemService;
import io.softa.starter.studio.release.upgrade.DeploymentExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DesignDeploymentServiceImplTest {

    @Test
    void executeDeploymentClosesReleasedWorkItemsAfterSuccessfulProdDeploy() {
        DesignDeploymentServiceImpl service = Mockito.spy(new DesignDeploymentServiceImpl());
        DeploymentExecutor deploymentExecutor = mock(DeploymentExecutor.class);
        DesignAppEnvService appEnvService = mock(DesignAppEnvService.class);
        DesignAppVersionService appVersionService = mock(DesignAppVersionService.class);
        DesignDeploymentVersionService deploymentVersionService = mock(DesignDeploymentVersionService.class);
        DesignWorkItemService workItemService = mock(DesignWorkItemService.class);
        ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);

        ReflectionTestUtils.setField(service, "deploymentExecutor", deploymentExecutor);
        ReflectionTestUtils.setField(service, "appEnvService", appEnvService);
        ReflectionTestUtils.setField(service, "appVersionService", appVersionService);
        ReflectionTestUtils.setField(service, "deploymentVersionService", deploymentVersionService);
        ReflectionTestUtils.setField(service, "workItemService", workItemService);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        doReturn(true).when(service).updateOne(any(DesignDeployment.class));

        when(deploymentExecutor.convertToUpgradePackages(anyList())).thenReturn(List.<MetadataUpgradePackage>of());

        DesignDeployment deployment = new DesignDeployment();
        deployment.setId(10L);
        deployment.setMergedContent(JsonUtils.objectToJsonNode(List.of()));

        DesignAppEnv targetEnv = new DesignAppEnv();
        targetEnv.setId(100L);
        targetEnv.setEnvType(DesignAppEnvType.PROD);
        targetEnv.setAsyncUpgrade(false);

        DesignDeploymentVersion deploymentVersion = new DesignDeploymentVersion();
        deploymentVersion.setDeploymentId(10L);
        deploymentVersion.setVersionId(20L);
        when(deploymentVersionService.searchList(any(FlexQuery.class))).thenReturn(List.of(deploymentVersion));

        DesignWorkItem doneWorkItem = new DesignWorkItem();
        doneWorkItem.setId(1L);
        doneWorkItem.setVersionId(20L);
        doneWorkItem.setStatus(DesignWorkItemStatus.DONE);

        DesignWorkItem closedWithoutTime = new DesignWorkItem();
        closedWithoutTime.setId(2L);
        closedWithoutTime.setVersionId(20L);
        closedWithoutTime.setStatus(DesignWorkItemStatus.CLOSED);

        DesignWorkItem alreadyClosed = new DesignWorkItem();
        alreadyClosed.setId(3L);
        alreadyClosed.setVersionId(20L);
        alreadyClosed.setStatus(DesignWorkItemStatus.CLOSED);
        alreadyClosed.setClosedTime(LocalDateTime.parse("2026-03-30T12:00:00"));

        when(workItemService.searchList(any(FlexQuery.class)))
                .thenReturn(List.of(doneWorkItem, closedWithoutTime, alreadyClosed));

        DesignAppVersion targetVersion = new DesignAppVersion();
        targetVersion.setId(20L);
        targetVersion.setStatus(DesignAppVersionStatus.SEALED);
        when(appVersionService.getById(20L)).thenReturn(Optional.of(targetVersion));

        ReflectionTestUtils.invokeMethod(service, "executeDeployment", deployment, targetEnv, 20L);

        assertEquals(DesignWorkItemStatus.CLOSED, doneWorkItem.getStatus());
        assertNotNull(doneWorkItem.getClosedTime());
        assertEquals(deployment.getFinishedTime(), doneWorkItem.getClosedTime());
        assertEquals(DesignWorkItemStatus.CLOSED, closedWithoutTime.getStatus());
        assertEquals(deployment.getFinishedTime(), closedWithoutTime.getClosedTime());
        assertEquals(LocalDateTime.parse("2026-03-30T12:00:00"), alreadyClosed.getClosedTime());
        verify(workItemService, times(2)).updateOne(any(DesignWorkItem.class));
        verify(workItemService).updateOne(argThat(workItem ->
                workItem.getId().equals(1L)
                        && workItem.getStatus() == DesignWorkItemStatus.CLOSED
                        && workItem.getClosedTime() != null));
        verify(workItemService).updateOne(argThat(workItem ->
                workItem.getId().equals(2L)
                        && workItem.getStatus() == DesignWorkItemStatus.CLOSED
                        && workItem.getClosedTime() != null));
        verify(appVersionService).freezeVersion(20L);
    }

    @Test
    void executeDeploymentDoesNotCloseWorkItemsOutsideProd() {
        DesignDeploymentServiceImpl service = Mockito.spy(new DesignDeploymentServiceImpl());
        DeploymentExecutor deploymentExecutor = mock(DeploymentExecutor.class);
        DesignAppEnvService appEnvService = mock(DesignAppEnvService.class);
        DesignDeploymentVersionService deploymentVersionService = mock(DesignDeploymentVersionService.class);
        DesignWorkItemService workItemService = mock(DesignWorkItemService.class);
        ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);

        ReflectionTestUtils.setField(service, "deploymentExecutor", deploymentExecutor);
        ReflectionTestUtils.setField(service, "appEnvService", appEnvService);
        ReflectionTestUtils.setField(service, "appVersionService", mock(DesignAppVersionService.class));
        ReflectionTestUtils.setField(service, "deploymentVersionService", deploymentVersionService);
        ReflectionTestUtils.setField(service, "workItemService", workItemService);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        doReturn(true).when(service).updateOne(any(DesignDeployment.class));

        when(deploymentExecutor.convertToUpgradePackages(anyList())).thenReturn(List.<MetadataUpgradePackage>of());

        DesignDeployment deployment = new DesignDeployment();
        deployment.setId(11L);
        deployment.setMergedContent(JsonUtils.objectToJsonNode(List.of()));

        DesignAppEnv targetEnv = new DesignAppEnv();
        targetEnv.setId(101L);
        targetEnv.setEnvType(DesignAppEnvType.TEST);
        targetEnv.setAsyncUpgrade(false);

        ReflectionTestUtils.invokeMethod(service, "executeDeployment", deployment, targetEnv, 21L);

        assertNull(deployment.getErrorMessage());
        verifyNoInteractions(deploymentVersionService);
        verifyNoInteractions(workItemService);
    }
}
