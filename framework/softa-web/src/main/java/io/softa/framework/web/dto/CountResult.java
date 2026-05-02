package io.softa.framework.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(name = "CountResult", description = "Result of /count API. Either `total` or `groups` is set based on whether `groupBy` is specified in the request.")
public class CountResult {

    @Schema(description = "Total count when `groupBy` is not specified in the request. Null otherwise.")
    private Long total;

    @Schema(description = "Group counting rows when `groupBy` is specified. Each row contains the grouped field values and a `count` field. Null otherwise.")
    private List<Map<String, Object>> groups;
}
