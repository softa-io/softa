package io.softa.framework.web.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.orm.domain.SubQuery;

/**
 * GetByIdParams for /getById API.
 */
@Data
@Schema(name = "GetByIdParams")
public class GetByIdParams {

    @Schema(description = "Data ID, number or string type.", type = "string", requiredMode = Schema.RequiredMode.REQUIRED)
    private Serializable id;

    @Schema(description = "Field names. If not specified, it defaults to all visible fields.", example = "[]")
    private List<String> fields;

    @Schema(description = "SubQuery parameters for relational fields.", example = "{}")
    private Map<String, SubQuery> subQueries;

    @Schema(description = "Effective date for timeline model, default is `Today`.")
    private LocalDate effectiveDate;

}
