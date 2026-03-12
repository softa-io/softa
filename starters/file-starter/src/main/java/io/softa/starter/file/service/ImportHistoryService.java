package io.softa.starter.file.service;

import java.util.List;
import java.util.Map;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.file.entity.ImportHistory;

/**
 * ImportHistory service interface
 */
public interface ImportHistoryService extends EntityService<ImportHistory, Long> {

    /**
     * List current user's import history by model name.
     *
     * @param modelName model name
     * @return import history list
     */
    List<Map<String, Object>> listMyImportHistory(String modelName);
}
