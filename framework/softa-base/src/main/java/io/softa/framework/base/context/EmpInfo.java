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
}
