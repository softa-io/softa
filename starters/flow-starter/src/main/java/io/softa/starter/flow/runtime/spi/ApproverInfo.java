package io.softa.starter.flow.runtime.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Approver information holder used by approver resolution.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "ApproverInfo")
public class ApproverInfo {

    @Schema(description = "User ID")
    private Long userId;

    @Schema(description = "User name")
    private String userName;

    @Schema(description = "Department ID")
    private Long deptId;

    @Schema(description = "Department name")
    private String deptName;

    public ApproverInfo(Long userId, String userName) {
        this.userId = userId;
        this.userName = userName;
    }

    /**
     * Convenience: return userId as String.
     */
    public String getUserIdAsString() {
        return userId == null ? null : userId.toString();
    }
}

