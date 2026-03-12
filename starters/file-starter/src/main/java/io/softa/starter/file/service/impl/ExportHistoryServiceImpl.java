package io.softa.starter.file.service.impl;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.service.ExportHistoryService;

/**
 * ExportHistory Service Implementation
 */
@Service
public class ExportHistoryServiceImpl extends EntityServiceImpl<ExportHistory, Long> implements ExportHistoryService {

    @Override
    public List<Map<String, Object>> listMyExportHistory(String modelName) {
        Long userId = ContextHolder.getContext().getUserId();
        Filters filters = new Filters()
                .eq(ExportHistory::getCreatedId, userId)
                .eq(ExportHistory::getModelName, modelName);
        Orders orders = Orders.ofDesc(ExportHistory::getCreatedTime);
        return this.modelService.searchList(this.modelName, new FlexQuery(filters, orders));
    }
}
