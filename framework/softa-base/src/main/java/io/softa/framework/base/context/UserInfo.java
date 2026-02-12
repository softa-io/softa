package io.softa.framework.base.context;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;

/**
 * Basic info of the current user
 */
@Data
public class UserInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String name;
    private Language language;
    private Timezone timezone;
    private String photoUrl;
    private Long tenantId;

}
