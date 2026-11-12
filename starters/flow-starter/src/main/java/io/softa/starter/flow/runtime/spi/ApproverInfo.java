package io.softa.starter.flow.runtime.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Approver information holder used by approver resolution: a user, named, and the department a role query narrows
 * its holders by.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApproverInfo {

    private Long userId;

    private String userName;

    private Long deptId;

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
