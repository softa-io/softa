package io.softa.starter.studio.meta.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.dto.ModelCodeDTO;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.service.DesignModelService;
import io.softa.starter.studio.release.entity.DesignApp;
import io.softa.starter.studio.release.service.DesignAppService;
import io.softa.starter.studio.template.ddl.context.DdlContextBuilder;
import io.softa.starter.studio.template.ddl.context.ModelDdlCtx;
import io.softa.starter.studio.template.ddl.dialect.DdlDialectRegistry;
import io.softa.starter.studio.template.enums.DesignCodeLang;
import io.softa.starter.studio.template.generator.CodeGenerator;

/**
 * DesignModel Model Service Implementation
 */
@Service
public class DesignModelServiceImpl extends EntityServiceImpl<DesignModel, Long> implements DesignModelService {

    @Autowired
    private DdlDialectRegistry ddlDialectRegistry;

    @Autowired
    private DesignAppService appService;

    @Autowired
    private CodeGenerator codeGenerator;

    /**
     * Preview the DDL SQL of model, including table creation and index creation
     *
     * @param id Model ID
     * @return Model DDL SQL
     */
    @Override
    public String previewDDL(Long id) {
        SubQueries subQueries = new SubQueries().expand(DesignModel::getModelFields)
                .expand(DesignModel::getModelIndexes);
        DesignModel designModel = this.getById(id, subQueries).orElse(null);
        if (designModel == null) {
            throw new IllegalArgumentException("The designModel id {0} does not exist!", id);
        }
        ModelDdlCtx model = DdlContextBuilder.fromCreatedModel(designModel);
        DatabaseType databaseType = appService.getFieldValue(designModel.getAppId(), DesignApp::getDatabaseType);
        StringBuilder ddl = new StringBuilder()
                .append(ddlDialectRegistry.getDialect(databaseType).createTableDDL(model))
                .append("\n");
        if (CollectionUtils.isEmpty(model.getCreatedIndexes())) {
            return ddl.toString();
        }
        ddl.append("\n")
                .append(ddlDialectRegistry.getDialect(databaseType).alterIndexDDL(model))
                .append("\n");
        return ddl.toString();
    }

    /**
     * Preview the current model code files for the requested language.
     *
     * @param id Model ID
     * @return Generated code files of the current model
     */
    @Override
    public ModelCodeDTO previewCode(Long id, DesignCodeLang codeLang) {
        List<ModelCodeDTO> modelCodes = previewAllCode(id);
        if (codeLang != null) {
            return modelCodes.stream()
                    .filter(modelCode -> codeLang == modelCode.getCodeLang())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The code language {0} is not configured for model id {1}!", codeLang.getCode(), id));
        }
        if (modelCodes.size() == 1) {
            return modelCodes.getFirst();
        }
        throw new IllegalArgumentException("Multiple code languages are configured for model id {0}, please specify codeLang!", id);
    }

    @Override
    public List<ModelCodeDTO> previewAllCode(Long id) {
        DesignModel designModel = this.getById(id, new SubQueries().expand(DesignModel::getModelFields))
                .orElseThrow(() -> new IllegalArgumentException("The designModel id {0} does not exist!", id));
        if (designModel.getAppId() == null) {
            throw new IllegalArgumentException("The appId of the model cannot be null!");
        }
        DesignApp designApp = appService.getById(designModel.getAppId())
                .orElseThrow(() -> new IllegalArgumentException("The app id {0} does not exist!", designModel.getAppId()));
        return codeGenerator.generateAllModelCodes(designApp.getPackageName(), designModel);
    }

}
