package io.softa.starter.studio.meta.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.dto.ModelCodeDTO;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.template.enums.DesignCodeLang;

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
     * Preview the current model code files for the requested language.
     *
     * @param id Model ID
     * @return Generated code files of the current model
     */
    ModelCodeDTO previewCode(Long id, DesignCodeLang codeLang);

    List<ModelCodeDTO> previewAllCode(Long id);

}
