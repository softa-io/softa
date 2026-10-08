package io.softa.starter.file.excel.export.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.ListUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.entity.ExportTemplateField;
import io.softa.starter.file.excel.export.ResolvedTemplateSheet;
import io.softa.starter.file.service.ExportTemplateFieldService;

@Component
public class ExportTemplateResolver {

    @Autowired
    private ExportTemplateFieldService exportTemplateFieldService;

    /**
     * Resolve template fields into headers, fetch fields and final export fields.
     */
    public ResolvedTemplateSheet resolve(ExportTemplate exportTemplate) {
        List<String> headers = new ArrayList<>();
        List<String> fetchFields = new ArrayList<>();
        List<String> exportFields = new ArrayList<>();
        Filters filters = new Filters().eq(ExportTemplateField::getTemplateId, exportTemplate.getId());
        Orders orders = Orders.ofAsc(ExportTemplateField::getSequence);
        // Read past the caller's row scope: which columns a template is made of is not the caller's
        // data. The endpoint gate admitted them to export; under their scope these child rows — which
        // no role grants and the template's own declared scope does not reach — came back empty, and
        // the export failed on a template with no fields. The exported rows are still read, scoped
        // and masked, as the caller.
        Context ctx = ContextHolder.cloneContext();
        ctx.setSkipPermissionCheck(true);
        List<ExportTemplateField> exportFieldsConfig = ContextHolder.callWith(ctx,
                () -> exportTemplateFieldService.searchList(new FlexQuery(filters, orders)));
        Assert.notEmpty(exportFieldsConfig, "The export template must have at least one field.");
        exportFieldsConfig.forEach(exportField -> {
            fetchFields.add(exportField.getFieldName());
            if (Boolean.TRUE.equals(exportField.getIgnored())) {
                return;
            }
            exportFields.add(exportField.getFieldName());
            if (StringUtils.isNotBlank(exportField.getCustomHeader())) {
                headers.add(exportField.getCustomHeader());
            } else {
                MetaField lastField = ModelManager.getLastFieldOfCascaded(exportTemplate.getModelName(),
                        exportField.getFieldName());
                headers.add(lastField.getLabel());
            }
        });
        return new ResolvedTemplateSheet(headers, fetchFields, exportFields);
    }

    /**
     * Convert queried rows into table data using resolved export fields.
     */
    public List<List<Object>> resolveRows(ResolvedTemplateSheet resolvedTemplateSheet, List<Map<String, Object>> rows) {
        return ListUtils.convertToTableData(resolvedTemplateSheet.getExportFields(), rows);
    }
}
