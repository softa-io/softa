package io.softa.starter.designer.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.designer.dto.ModelCodeDTO;
import io.softa.starter.designer.entity.DesignModel;

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

    /**
     * Preview the current model code, including Java class code for Entity, Service, ServiceImpl, and Controller
     *
     * @param id Model ID
     * @return Code text of the current model
     */
    ModelCodeDTO previewCode(Long id);

}