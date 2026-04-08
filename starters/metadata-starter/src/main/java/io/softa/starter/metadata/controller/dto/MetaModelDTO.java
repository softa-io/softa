package io.softa.starter.metadata.controller.dto;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.orm.domain.Orders;

/**
 * MetaModelDTO
 */
@Data
@Schema(name = "MetaModelDTO")
public class MetaModelDTO {
    private String labelName;
    private String modelName;
    private String description;
    private List<String> displayName;
    private List<String> searchName;
    private Orders defaultOrder;
    private boolean timeline;
    private Map<String, MetaFieldDTO> modelFields;
}
