package io.softa.starter.designer.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.ListUtils;
import io.softa.framework.orm.utils.MapUtils;
import io.softa.starter.designer.dto.ModelChangesDTO;
import io.softa.starter.designer.entity.*;
import io.softa.starter.designer.service.DesignAppEnvService;
import io.softa.starter.designer.service.DesignAppVersionService;
import io.softa.starter.designer.version.VersionControl;
import io.softa.starter.designer.version.VersionDdl;
import io.softa.starter.designer.version.VersionPublish;
import io.softa.starter.metadata.constant.MetadataConstant;

/**
 * DesignAppVersion Model Service Implementation
 */
@Service
public class DesignAppVersionServiceImpl extends EntityServiceImpl<DesignAppVersion, Long> implements DesignAppVersionService {

    @Autowired
    private VersionControl versionControl;

    @Autowired
    private VersionDdl versionDdl;

    @Autowired
    private VersionPublish versionPublish;

    @Lazy
    @Autowired
    private DesignAppEnvService appEnvService;

    /**
     * Create a new App version.
     *
     * @param appVersion App version object
     * @return id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOne(DesignAppVersion appVersion) {
        this.lockHistoricalVersion(appVersion.getEnvId());
        this.fillAppVersionFields(appVersion);
        return super.createOne(appVersion);
    }

    /**
     * Lock historical versions of the specified environment.
     * The historical version is locked to prevent the published content from being modified.
     *
     * @param envId Environment ID
     */
    private void lockHistoricalVersion(Long envId) {
        List<String> fields = ListUtils.getLambdaFields(
                DesignAppVersion::getName,
                DesignAppVersion::getAppId,
                DesignAppVersion::getLocked,
                DesignAppVersion::getPublished);
        Filters filters = new Filters().eq(DesignAppVersion::getEnvId, envId)
                .eq(DesignAppVersion::getLocked, false);
        FlexQuery flexQuery = new FlexQuery().select(fields).where(filters);
        List<DesignAppVersion> appVersions = this.searchList(flexQuery);
        if (appVersions.isEmpty()) {
            return;
        }
        List<Map<String, Object>> dataToLock = new ArrayList<>();
        for (DesignAppVersion appVersion : appVersions) {
            Assert.isTrue(appVersion.getPublished(),
                    "The version {0} of current Env has not published, cannot create new version!", appVersion.getName());
            dataToLock.add(MapUtils.strObj()
                    .put(ModelConstant.ID, appVersion.getId())
                    .put(DesignAppVersion::getLocked, true)
                    .build());
        }
        modelService.updateList(modelName, dataToLock);
    }

    /**
     * Reload App env changes to current version.
     *
     * @param id Version ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reloadAppVersion(Long id) {
        DesignAppVersion appVersion = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("The specified version does not exist! {0}", id));
        if (Boolean.TRUE.equals(appVersion.getLocked())) {
            throw new BusinessException("""
                    The version is locked and historical version published content cannot be modified.
                    Please modify the published content in the new version!""");
        }
        this.fillAppVersionFields(appVersion);
        return this.updateOne(appVersion);
    }


    /**
     * Fill in the fields of the App version object.
     *
     * @param appVersion App version object
     */
    private void fillAppVersionFields(DesignAppVersion appVersion) {
        LocalDateTime versionedTime = LocalDateTime.now();
        appVersion.setLastVersionedTime(versionedTime);
        List<ModelChangesDTO> modelChangesDTOList = new ArrayList<>();
        ModelChangesDTO modelChanges = null;
        ModelChangesDTO fieldChanges = null;
        ModelChangesDTO indexChanges = null;
        DesignAppEnv appEnv = appEnvService.getById(appVersion.getEnvId())
                .orElseThrow(() -> new IllegalArgumentException("The envId {0} of app version {1} does not exist!",
                        appVersion.getEnvId(), appVersion.getName()));
        for (String versionedModel : MetadataConstant.VERSION_CONTROL_MODELS.keySet()) {
            ModelChangesDTO modelChangesDTO = versionControl.getModelChanges(appEnv, versionedModel, versionedTime);
            if (modelChangesDTO == null) {
                continue;
            }
            modelChangesDTOList.add(modelChangesDTO);
            if (DesignModel.class.getSimpleName().equals(versionedModel)) {
                modelChanges = modelChangesDTO;
            } else if (DesignField.class.getSimpleName().equals(versionedModel)) {
                fieldChanges = modelChangesDTO;
            } else if (DesignModelIndex.class.getSimpleName().equals(versionedModel)) {
                indexChanges = modelChangesDTO;
            }
        }
        // Update the version with the latest change records
        appVersion.setPublished(false);
        appVersion.setVersionedContent(JsonUtils.objectToJsonNode(modelChangesDTOList));
        appVersion.setDdlTable(versionDdl.generateTableDDL(modelChanges, fieldChanges));
        appVersion.setDdlIndex(versionDdl.generateIndexDDL(indexChanges));
    }

    /**
     * Publish the version to the target environment.
     *
     * @param id    Version ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long id) {
        DesignAppVersion appVersion = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("The specified version does not exist! {0}", id));
        DesignAppEnv appEnv = appEnvService.getById(appVersion.getEnvId())
                .orElseThrow(() -> new IllegalArgumentException("The specified environment does not exist! {0}", appVersion.getEnvId()));
        Assert.notBlank(appEnv.getUpgradeEndpoint(),
                "The interface address of the App-Env {0}: {1} cannot be empty!", appEnv.getAppCode(), appEnv.getName());
        Assert.notTrue(appEnv.getAutoUpgrade(),
                "The App environment {0} is set to auto-upgrade, no manual publish is required!", appEnv.getName());
        // Read the change records of the current version and publish them
        List<ModelChangesDTO> versionedContent = JsonUtils.jsonNodeToObject(appVersion.getVersionedContent(), new TypeReference<>() {});
        versionPublish.upgradeVersion(appEnv, appVersion, versionedContent);
    }

}