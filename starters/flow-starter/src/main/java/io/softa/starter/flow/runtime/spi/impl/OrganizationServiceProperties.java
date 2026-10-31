package io.softa.starter.flow.runtime.spi.impl;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the default {@link FlowOrgService}: the models and fields it reads
 * the organization from. The defaults follow the HR conventions permission-starter already reads
 * ({@code Employee}, {@code Department}, user-starter's {@code UserRoleRel}, {@code Role} and
 * {@code UserAccount}), so an application on those models configures nothing.
 */
@Data
@ConfigurationProperties(prefix = "softa.flow.org")
public class OrganizationServiceProperties {

    /** Employee model. */
    private String employeeModel = "Employee";
    /** Field on the employee model that holds the employee's login account id — the approver id. */
    private String accountIdField = "userId";
    /** Field on the employee model that holds the display name. */
    private String nameField = "fullName";
    /** Field on the employee model that holds the department id. */
    private String deptIdField = "departmentId";
    /** Field on the employee model that holds the job position id. */
    private String positionIdField = "jobPositionId";
    /** Field on the employee model that holds the direct supervisor's employee id. */
    private String supervisorIdField = "piEmployeeId";

    /** Department model. */
    private String departmentModel = "Department";
    /** Field on the department model that holds its name. */
    private String deptNameField = "name";
    /** Field on the department model that holds the department leader's employee id. */
    private String deptLeaderIdField = "picEmpId";
    /** Field on the department model that holds the department HRBP's employee id. */
    private String deptHrbpIdField = "hrbpEmpId";
    /** Field on the department model that holds the parent department id. */
    private String parentDeptIdField = "parentId";

    /** User-role assignment model. */
    private String roleModel = "UserRoleRel";
    /** Field on the assignment model that holds the role id. */
    private String roleIdField = "roleId";
    /** Field on the assignment model that holds the login account id. */
    private String roleUserIdField = "userId";
    /** Role model, for looking roles up by code. */
    private String roleDefinitionModel = "Role";
    /** Field on the role model that holds the role code. */
    private String roleCodeField = "code";

    /** Login account model (user-starter's), whose status says whether an account signs in. */
    private String accountModel = "UserAccount";
    /** Field on the account model that holds its status. */
    private String accountStatusField = "status";
    /**
     * The account statuses that sign in, one of which an approver's account must hold. An account in any other —
     * not yet invited, invited but not accepted, frozen, its membership ended — counts as nobody, as an employee
     * without an account does. Not applied when the application has no account model.
     */
    private List<String> approverAccountStatuses = List.of("Active");

    /** Maximum depth of department traversal, up or down (guards against cycles). */
    private int maxDeptDepth = 10;
}
