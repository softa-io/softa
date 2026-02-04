package io.softa.framework.base.context;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

/**
 * Basic info of the current user
 */
@Data
public class EmpInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String empId;
    private String name;
    private String email;
    private String phone;
    private String photoUrl;
    private String deptId;
    private String deptName;
    private String positionId;
    private String positionName;
    private String companyId;
    private String companyName;
    private String tenantId;
}
