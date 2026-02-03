package io.softa.framework.orm.entity;

import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract model
 */
public abstract class AbstractModel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Abstract method to get the ID of the model.
     */
    public abstract Serializable getId();
}