package io.softa.framework.web.task.params;

import java.io.Serializable;
import java.util.Set;
import lombok.Data;

/**
 */
@Data
public class RecomputeHandlerParams implements TaskHandlerParams {
    private String model;
    private Set<String> fields;
    private Set<Serializable> ids;
}
