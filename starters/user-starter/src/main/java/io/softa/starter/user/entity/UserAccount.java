package io.softa.starter.user.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.user.enums.AccountStatus;

/**
 * UserAccount Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true,
        searchName = {"nickname", "username"}
)
@Index(indexName = "uk_user_account_email", fields = {"email"}, unique = true,
        message = "This email is already registered.")
public class UserAccount extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field
    private String nickname;

    @Field
    private String username;

    @Field(length = 256, copyable = false)
    private String password;

    @Field(copyable = false)
    private String passwordSalt;

    @Field
    private String email;

    @Field
    private String mobile;

    @Field(copyable = false)
    private LocalDateTime activationTime;

    @Field(label = "Policy ID")
    private Long policyId;

    @Field(copyable = false)
    private Boolean locked;

    @Field
    private AccountStatus status;
    
    @Field(label = "Roles", fieldType = FieldType.MANY_TO_MANY,
            relatedModel = Role.class, joinModel = UserRoleRel.class,
            joinLeft = "userId", joinRight = "roleId")
    private List<Long> roles;
}
