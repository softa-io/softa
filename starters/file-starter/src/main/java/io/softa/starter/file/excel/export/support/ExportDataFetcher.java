package io.softa.starter.file.excel.export.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.AccessScope;
import io.softa.framework.orm.service.ModelService;

@Component
public class ExportDataFetcher {

    @Autowired
    private ModelService<?> modelService;

    @Autowired(required = false)
    private List<ExportRowProcessor> rowProcessors = List.of();

    /**
     * Query export rows page by page, apply the model's {@link ExportRowProcessor}s, then the custom export
     * handler if configured.
     *
     * <p>Read as an {@link AccessType#EXPORT}: the rows an export reaches are those of the roles that
     * may export the model, which need not be every row the caller may look at on screen.
     */
    public List<Map<String, Object>> fetchRows(String modelName, String handlerName, FlexQuery flexQuery) {
        Page<Map<String, Object>> page = Page.ofCursorPage(BaseConstant.MAX_BATCH_SIZE);
        List<Map<String, Object>> exportedRows = new ArrayList<>();
        do {
            Page<Map<String, Object>> current = page;
            page = AccessScope.callAs(AccessType.EXPORT, () -> modelService.searchPage(modelName, flexQuery, current));
            if (!page.getRows().isEmpty()) {
                exportedRows.addAll(page.getRows());
            }
        } while (page.toNext());
        for (ExportRowProcessor processor : rowProcessors) {
            if (processor.supports(modelName)) {
                processor.process(modelName, exportedRows);
            }
        }
        executeCustomHandler(handlerName, exportedRows);
        return exportedRows;
    }

    private void executeCustomHandler(String handlerName, List<Map<String, Object>> rows) {
        if (StringUtils.isBlank(handlerName)) {
            return;
        }
        if (!StringTools.isBeanName(handlerName)) {
            throw new IllegalArgumentException("The name of custom export handler `{0}` is invalid.", handlerName);
        }
        try {
            CustomExportHandler handler = SpringContextUtils.getBean(handlerName, CustomExportHandler.class);
            handler.handleExportData(rows);
        } catch (NoSuchBeanDefinitionException e) {
            throw new IllegalArgumentException("The custom export handler `{0}` is not found.", handlerName);
        }
    }
}
