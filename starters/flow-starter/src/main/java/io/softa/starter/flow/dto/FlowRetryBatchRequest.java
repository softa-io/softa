package io.softa.starter.flow.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Run the steps after an approval again, from the monitoring console: for one or several approvals that failed in them. */
@Data
@Schema(name = "FlowRetryBatchRequest")
public class FlowRetryBatchRequest {

    @NotEmpty(message = "Choose at least one instance")
    @Size(max = 100, message = "At most 100 instances can be run again at once")
    @Schema(description = "Runtime instance ids")
    private List<String> instanceIds;

    @Size(max = 256, message = "The note can be at most 256 characters")
    @Schema(description = "Optional note, recorded on each in its approval history")
    private String comment;
}
