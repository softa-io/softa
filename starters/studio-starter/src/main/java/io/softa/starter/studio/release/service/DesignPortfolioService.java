package io.softa.starter.studio.release.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.release.entity.DesignPortfolio;
import io.softa.starter.studio.release.enums.DesignPortfolioStatus;

/**
 * DesignPortfolio Model Service Interface
 */
public interface DesignPortfolioService extends EntityService<DesignPortfolio, Long> {

    /**
     * Transition the portfolio status with business validation.
     *
     * @param id portfolio ID
     * @param targetStatus target status
     */
    void transitionStatus(Long id, DesignPortfolioStatus targetStatus);

}
