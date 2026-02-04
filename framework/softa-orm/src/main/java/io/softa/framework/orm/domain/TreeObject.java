package io.softa.framework.orm.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

import io.softa.framework.orm.entity.AbstractModel;

/**
 * Tree objects
 */
@Data
public class TreeObject<T extends AbstractModel> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private T parent;

    private List<T> children;
}
