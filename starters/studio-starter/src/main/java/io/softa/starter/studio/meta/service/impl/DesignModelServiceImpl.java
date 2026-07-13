package io.softa.starter.studio.meta.service.impl;

import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.ddl.context.ModelDdlCtx;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignModelIndex;
import io.softa.starter.studio.meta.service.DesignFieldService;
import io.softa.starter.studio.meta.service.DesignModelIndexService;
import io.softa.starter.studio.meta.service.DesignModelService;
import io.softa.starter.studio.release.connector.ConnectorFactory;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.service.DesignAppEnvService;
import io.softa.starter.studio.release.ddl.context.DdlContextBuilder;

/**
 * DesignModel Model Service Implementation
 */
@Service
public class DesignModelServiceImpl extends EntityServiceImpl<DesignModel, Long> implements DesignModelService {

    @Autowired
    private ConnectorFactory connectorFactory;

    @Autowired
    private DesignAppEnvService appEnvService;

    @Autowired
    private DesignFieldService fieldService;

    @Autowired
    private DesignModelIndexService indexService;

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
        // FK physical types are read straight from the design fields (relatedFieldType, stamped at
        // edit time by DesignFieldController); fromCreatedModel maps them into the DDL context.
        ModelDdlCtx model = DdlContextBuilder.fromCreatedModel(designModel);
        // Render through the owning env's connector dialect (Softa runtime → builtin
        // annotation dialect, identical to publish + the boot scanner). A design model is per-env, so
        // its envId fixes the target flavor unambiguously — no separate envId param is needed.
        DesignAppEnv env = appEnvService.getById(designModel.getEnvId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The env {0} of design model {1} does not exist!", designModel.getEnvId(), id));
        DdlDialect dialect = connectorFactory.forEnv(env).dialect();
        return dialect.createTableDDL(model) + "\n";
    }

    /**
     * Cascade-delete the model's children: deleting a {@link DesignModel} also removes its
     * {@link DesignField} and {@link DesignModelIndex} rows, so the no-code lane never leaves orphan
     * children (a child whose parent model is gone) in the env's {@code design_*} workspace.
     *
     * <p>The ORM does not cascade {@code ONE_TO_MANY} deletes and there is no DB foreign key on the
     * child {@code model_id}, so without this the children would accumulate as garbage — the
     * publish/merge differ already excludes such orphans, but never cleaned them up.
     *
     * <p>Children are matched by the rename-stable surrogate FK {@code modelId} — the same join the
     * {@code DesignModel.modelFields} / {@code modelIndexes} relations use. {@code modelId} is a
     * globally unique distributed id, so {@code modelId IN (parentIds)} is inherently scoped to each
     * parent's own env and can never reach another env's rows. Children drop before the parent, and
     * the whole operation is one transaction so a failure rolls back cleanly.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        cascadeDeleteChildren(ids);
        return super.deleteByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteById(Long id) {
        return this.deleteByIds(Collections.singletonList(id));
    }

    private void cascadeDeleteChildren(List<Long> modelIds) {
        if (CollectionUtils.isEmpty(modelIds)) {
            return;
        }
        fieldService.deleteByFilters(new Filters().in(DesignField::getModelId, modelIds));
        indexService.deleteByFilters(new Filters().in(DesignModelIndex::getModelId, modelIds));
    }

}
