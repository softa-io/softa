package io.softa.starter.studio.meta.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.meta.entity.DesignModel;

/**
 * DesignModel Model Service Interface
 */
public interface DesignModelService extends EntityService<DesignModel, Long> {

    /**
     * Preview the DDL SQL of model, including table creation and index creation
     *
     * @param id Model ID
     * @return Model DDL SQL
     */
    String previewDDL(Long id);

}
