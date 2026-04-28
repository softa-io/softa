package io.softa.starter.studio.template.ddl;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.placeholder.TemplateEngine;
import io.softa.starter.studio.template.ddl.context.ModelDdlCtx;

class PebTest {
    @Test
    void debugOnlyDescChanged() {
        ModelDdlCtx model = new ModelDdlCtx();
        model.setTableName("user_role");
        model.setLabelName("User Role");
        model.setDescription("123");
        model.setDescriptionChanged(true);
        model.setRenamed(false);
        String sql = TemplateEngine.renderFilePath("templates/sql/mysql/AlterTable.peb", Map.of("model", model));
        System.out.println("===BEGIN===");
        System.out.println(sql.replace("\n", "\\n\n"));
        System.out.println("===END===");
    }
}
