package io.softa.starter.es.service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;

/**
 * Common interface for ES service
 * @param <T> entity type stored in ES
 */
public interface ESService<T> {

    /**
     * ES paging query object data
     *
     * @param filters   filter conditions
     * @param orders    sort rules
     * @param page      paging object
     * @return a page of indexed data
     */
    Page<T> searchPage(Filters filters, Orders orders, Page<T> page);

}