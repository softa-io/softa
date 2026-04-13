package io.softa.starter.studio.release.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.entity.DesignPortfolio;
import io.softa.starter.studio.release.enums.DesignPortfolioStatus;
import io.softa.starter.studio.release.service.DesignPortfolioService;

/**
 * DesignPortfolio Model Service Implementation
 */
@Service
public class DesignPortfolioServiceImpl extends EntityServiceImpl<DesignPortfolio, Long> implements DesignPortfolioService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transitionStatus(Long id, DesignPortfolioStatus targetStatus) {
        Assert.notNull(targetStatus, "Target status is required.");
        DesignPortfolio portfolio = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("The designPortfolio id {0} does not exist!", id));
        DesignPortfolioStatus currentStatus = portfolio.getStatus();
        if (currentStatus == targetStatus) {
            return;
        }
        if (currentStatus != null) {
            boolean allowed = switch (currentStatus) {
                case ACTIVE -> targetStatus == DesignPortfolioStatus.ARCHIVED;
                case ARCHIVED -> targetStatus == DesignPortfolioStatus.ACTIVE;
            };
            Assert.isTrue(allowed, "Cannot transition Portfolio status from {0} to {1}.", currentStatus, targetStatus);
        }
        portfolio.setStatus(targetStatus);
        this.updateOne(portfolio);
    }

}
