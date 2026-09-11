package io.softa.starter.file.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.file.entity.ImportHistory;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.service.ImportHistoryService;
import io.softa.starter.file.service.ImportTemplateService;
import io.softa.starter.file.support.TemplateScope;

/**
 * ImportHistory service implementation
 */
@Service
public class ImportHistoryServiceImpl extends EntityServiceImpl<ImportHistory, Long> implements ImportHistoryService {

    /** Read-only, for the standalone-template exclusion the template list applies too. */
    @Autowired
    private ImportTemplateService importTemplateService;

    /**
     * The imports this page can show, which is the imports this page can START.
     *
     * <p>{@code ImportTemplateController.listByModel} offers a model's own templates AND its child
     * models' — that is how one employee page hands out the templates for addresses, family members
     * and the rest. The history asked for the model alone, so those imports ran from that page and
     * then were nowhere on it: the file uploaded, the rows landed, and the list said nothing had
     * happened. The only way to see them was a SQL client.
     *
     * <p>So it reads the same set the template list offers. The two answer one question — what was
     * imported from here — and were answering it differently.
     */
    @Override
    public List<Map<String, Object>> listMyImportHistory(String modelName) {
        Long userId = ContextHolder.getContext().getUserId();
        Set<String> modelNames = TemplateScope.of(modelName, this::standaloneModelNames);
        FlexQuery flexQuery = new FlexQuery()
                .where(new Filters()
                        .eq(ImportHistory::getCreatedId, userId)
                        .in(ImportHistory::getModelName, modelNames))
                .orderBy(Orders.ofDesc(ImportHistory::getCreatedTime))
                .setConvertType(ConvertType.REFERENCE);
        return this.modelService.searchList(this.modelName, flexQuery);
    }

    /** Models whose import templates belong only on their own page — see {@link TemplateScope}. */
    private Set<String> standaloneModelNames() {
        Filters standalone = new Filters().eq(ImportTemplate::getStandalone, true);
        return importTemplateService.searchList(new FlexQuery(standalone)).stream()
                .map(ImportTemplate::getModelName)
                .collect(Collectors.toSet());
    }
}
