package io.softa.starter.file.service;

import java.util.List;
import java.util.Map;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.file.entity.ExportHistory;

/**
 * ExportHistory Service Interface
 */
public interface ExportHistoryService extends EntityService<ExportHistory, Long> {

    /**
     * List current user's export history by model name.
     *
     * @param modelName model name
     * @return export history list
     */
    List<Map<String, Object>> listMyExportHistory(String modelName);
}
