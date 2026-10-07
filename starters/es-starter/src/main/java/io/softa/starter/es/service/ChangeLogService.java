package io.softa.starter.es.service;

import java.io.Serializable;
import java.util.List;

import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;

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

    /**
     * One record's history together with the history of the rows its named relations hold, as a
     * single page ordered by change time.
     *
     * <p>For a record kept across several models: an employee's personal details, bank account and
     * family members are each logged against their own rows, so the employee row's history alone is
     * most of the record missing. A one-to-one relation contributes the row it points at now; a
     * one-to-many relation contributes every row that has ever pointed back at this record,
     * including rows since deleted — found by the reference each log carries, and by the current
     * rows' ids for logs written before logs carried one.
     *
     * <p>Read access to the record is required, and read access to a relation's model; a relation
     * whose model the reader may not read is left out rather than refused, as the form leaves out
     * the table. Fields outside the reader's sensitive field sets are removed, and an update that
     * touched only those is not counted.
     *
     * <p>{@code referencing} reaches rows the model has no relation field for: every row of
     * another model that points at this record through a many-to-one or one-to-one, asked the same
     * way as a one-to-many — so a grant that points at a person is listed with the person's history, the
     * grants since revoked included.
     *
     * @param relations one-to-one and one-to-many field names of the model; others are refused
     * @param referencing {@code Model.field} of many-to-one or one-to-one fields to this record's
     *                    model; others are refused
     */
    Page<ChangeLog> getRecordChangeLog(String modelName, Serializable id, List<String> relations,
                                       List<String> referencing, Page<ChangeLog> page, String order,
                                       boolean includeCreation);

}
