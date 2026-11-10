package io.softa.starter.message.shared.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * The send records a bulk action applies to: the rows selected on a send record list.
 */
@Data
@Schema(description = "Send records to act on")
public class SendRecordIdsDTO {

    @NotEmpty
    @Schema(description = "Send record IDs", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> ids;
}
