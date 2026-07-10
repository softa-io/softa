package io.softa.framework.base.context;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;
import lombok.Data;

/**
 * Basic info of the current user.
 *
 * <p>Populated per-request by a {@code ContextEnricher} bean (e.g. zingkey-hcm's
 * {@code EmployeeContextEnricher}) into {@link Context#empInfo} — a single
 * source of truth that serves:
 * <ul>
 *   <li><b>Framework SQL macro substitution</b> — {@code FilterUnitParser}
 *       reads {@code empId / deptId / positionId / companyId} when expanding
 *       {@code {{USER_EMP_ID}} / {{USER_DEPT_ID}}} etc.</li>
 *   <li><b>Business service / template use</b> — services and notifiers read
 *       {@code name / email / phone / *Name} directly via
 *       {@code ContextHolder.getContext().getEmpInfo()}.</li>
 *   <li><b>HR scope contributors</b> — read {@code empId / deptId / companyId /
 *       managedDeptIds} from {@code ContextHolder.getContext().getEmpInfo()} to
 *       compile SELF / DIRECT_REPORTS / DEPT_SUBTREE / MANAGED_DEPARTMENTS /
 *       LEGAL_ENTITY filters.</li>
 * </ul>
 */
@Data
public class EmpInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long empId;
    private String name;
    private String email;
    private String phone;
    private String photoUrl;
    private Long deptId;
    private String deptName;
    private Long positionId;
    private String positionName;
    private Long companyId;
    private String companyName;
    private Long tenantId;
    /**
     * Departments the employee directly manages — populated by the
     * application's {@code ContextEnricher} (typically employee.id matching
     * a Department's pic-employee or HRBP-employee column). Subtree
     * expansion happens at scope-evaluation time in the consuming module's
     * scope contributor; this field carries only the directly-headed roots.
     *
     * <p>Empty (or null) means the user is not a department head — the
     * {@code MANAGED_DEPARTMENTS} scope degrades to fail-closed for that user.
     */
    private Set<Long> managedDeptIds;
}
