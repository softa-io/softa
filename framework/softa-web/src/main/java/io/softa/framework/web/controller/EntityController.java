package io.softa.framework.web.controller;

import java.io.Serializable;
import org.springframework.beans.factory.annotation.Autowired;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.service.EntityService;

/**
 * The base controller of entity.
 */
public abstract class EntityController<Service extends EntityService<T, K>, T extends AbstractModel, K extends Serializable> {

    @Autowired(required = false)
    protected Service service;

    /**
     * The size of operation data in a single API call cannot exceed the MAX_BATCH_SIZE.
     *
     * @param size data size
     */
    protected void validateBatchSize(int size) {
        Assert.isTrue(size <= BaseConstant.MAX_BATCH_SIZE,
                "The size of operation data cannot exceed the maximum {0} limit.", BaseConstant.MAX_BATCH_SIZE);
    }
}
