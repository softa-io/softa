package io.softa.starter.es.service;

import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;

import java.io.Serializable;

/**
 * ChangeLog service interface
 */
public interface ChangeLogService extends ESService<ChangeLog> {

    /**
     * Get the change log by the id of the business data model.
     *
     * @param modelName model name
     * @param id primary key id
     * @param order sort rule based on change time, default is reverse order, only support DESC, ASC string
     * @param includeCreation whether to include data at creation time, default is false, that is, not included
     * @return a page of change log list
     */
    Page<ChangeLog> getChangeLog(String modelName, Serializable id, Page<ChangeLog> page, String order, boolean includeCreation);

    /**
     * Get the change log by the primary key of the timeline model.
     *
     * @param modelName model name
     * @param sliceId primary key of the timeline model
     * @param page page object
     * @param order sort rule based on change time, default is reverse order, only support DESC, ASC string
     * @param includeCreation whether to include data at creation time, default is false, that is, not included
     * @return a page of change log list
     */
    Page<ChangeLog> getSliceChangeLog(String modelName, Serializable sliceId, Page<ChangeLog> page, String order, boolean includeCreation);

    /**
     * Get the ChangeLog page with the specified query conditions
     *
     * @param model     model name
     * @param flexQuery query conditions
     * @param page      page object
     * @return a page of list
     */
    Page<ChangeLog> searchPageByModel(String model, FlexQuery flexQuery, Page<ChangeLog> page);

}