package io.softa.starter.flow.runtime.spi.impl;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the default {@link MetadataOrganizationService}.
 * Maps metadata model names and field names to the organizational data model.
 */
@Data
@ConfigurationProperties(prefix = "softa.flow.org")
public class OrganizationServiceProperties {

    /** Metadata model name for employees / users. */
    private String employeeModel = "EmpInfo";

    /** Metadata model name for departments. */
    private String departmentModel = "DeptInfo";

    /** Field on the employee model that holds the user ID (primary key). */
    private String userIdField = "id";

    /** Field on the employee model that holds the display name. */
    private String nameField = "name";

    /** Field on the employee model that holds the department ID. */
    private String deptIdField = "deptId";

    /** Field on the department model that holds the department name. */
    private String deptNameField = "name";

    /** Field on the employee model that holds the position ID. */
    private String positionIdField = "positionId";

    /** Field on the employee model that holds the direct manager's user ID. */
    private String supervisorIdField = "managerId";

    /** Field on the department model that holds the department leader's user ID. */
    private String deptLeaderIdField = "leaderId";

    /** Field on the department model that holds the parent department ID. */
    private String parentDeptIdField = "parentId";

    /** Metadata model name for role assignments (user-role mapping). */
    private String roleModel = "EmpRole";

    /** Field on the role model that holds the role ID. */
    private String roleIdField = "roleId";

    /** Field on the role model that holds the user ID. */
    private String roleUserIdField = "empId";

    /** Maximum depth for recursive sub-department traversal (prevents infinite loops). */
    private int maxDeptDepth = 10;
}
