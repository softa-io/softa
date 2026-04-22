package io.softa.framework.web.dto;

import java.io.Serializable;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * SourceRecord
 * The parameters of current record in frontend.
 */
@Data
@Schema(name = "SourceRecord")
public class SourceRecord {

    @Schema(description = "The model of the source record.", example = "Employee")
    private String model;

    @Schema(description = "The ID of the source record.", example = "123")
    private Serializable recordId;

    @Schema(description = "The values of the source record, key is the field name, value is the field value.", example = "{\"name\": \"Tom\", \"age\": 18}")
    private Map<String, Object> values;

}
