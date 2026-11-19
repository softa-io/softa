package io.softa.starter.message.template;

import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.meta.FieldPathUse;
import io.softa.framework.orm.meta.FieldPaths;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.meta.WithheldFields;
import io.softa.framework.orm.service.PermissionService;

/** {@link TemplateModels} as the running application declares its models and permissions. */
@Component
public class MetadataTemplateModels implements TemplateModels {

    private final ObjectProvider<PermissionService> permissionService;

    public MetadataTemplateModels(ObjectProvider<PermissionService> permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean exists(String model) {
        return model != null && ModelManager.existModel(model);
    }

    @Override
    public MetaField field(String model, String fieldName) {
        return ModelManager.getModelFieldOrNull(model, fieldName);
    }

    @Override
    public String pathProblem(String model, String path) {
        return FieldPaths.problem(model, path, FieldPathUse.READ);
    }

    @Override
    public Map<String, Set<String>> withheld() {
        PermissionService permissions = permissionService.getIfAvailable();
        if (permissions == null) {
            return Map.of();
        }
        return WithheldFields.withRelations(permissions.getHiddenSensitiveFields());
    }
}
