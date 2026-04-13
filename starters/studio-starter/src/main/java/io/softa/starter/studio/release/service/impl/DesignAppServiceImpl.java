package io.softa.starter.studio.release.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.entity.DesignApp;
import io.softa.starter.studio.release.enums.DesignAppStatus;
import io.softa.starter.studio.release.service.DesignAppService;

/**
 * DesignApp Model Service Implementation
 */
@Service
public class DesignAppServiceImpl extends EntityServiceImpl<DesignApp, Long> implements DesignAppService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transitionStatus(Long id, DesignAppStatus targetStatus) {
        Assert.notNull(targetStatus, "Target status is required.");
        DesignApp app = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("The designApp id {0} does not exist!", id));
        DesignAppStatus currentStatus = app.getStatus();
        if (currentStatus == targetStatus) {
            return;
        }
        if (currentStatus != null) {
            boolean allowed = switch (currentStatus) {
                case ACTIVE -> targetStatus == DesignAppStatus.MAINTENANCE
                        || targetStatus == DesignAppStatus.DEPRECATED;
                case MAINTENANCE -> targetStatus == DesignAppStatus.ACTIVE
                        || targetStatus == DesignAppStatus.DEPRECATED;
                case DEPRECATED -> false;
            };
            Assert.isTrue(allowed, "Cannot transition App status from {0} to {1}.", currentStatus, targetStatus);
        }
        app.setStatus(targetStatus);
        this.updateOne(app);
    }

}
