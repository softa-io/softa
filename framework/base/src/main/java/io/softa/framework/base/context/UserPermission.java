package io.softa.framework.base.context;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * Role codes of the current user
 */
@Data
public class UserPermission implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Set<String> roleCodes;

    private Map<String, Set<String>> permissionCodes;
}
