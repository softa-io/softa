package io.softa.app.demo.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.WidgetType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.OnDelete;

/**
 * EmpInfo Model
 */
@Data
@Model(label = "Employee", businessKey = {"code"})
@EqualsAndHashCode(callSuper = true)
public class EmpInfo extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true, length = 100)
    private String name;

    @Field(copyable = false)
    private String code;

    // Value domain: the pattern is enforced on every write path and served to the UI as the input's own
    // check. widgetType only picks the control — there is no email validation anywhere but here.
    @Field(length = 128, widgetType = WidgetType.EMAIL,
            pattern = "[^@\\s]+@[^@\\s]+\\.[^@\\s]+", constraintMessage = "Enter a valid email address.")
    private String email;

    // onDelete = RESTRICT: a department that still has employees cannot be deleted.
    @Field(label = "Department", fieldType = FieldType.MANY_TO_ONE, relatedModel = DeptInfo.class,
            onDelete = OnDelete.RESTRICT)
    private Long deptId;

    @Field(label = "Projects Involved", fieldType = FieldType.MANY_TO_MANY,
            relatedModel = ProjectInfo.class, joinModel = EmpProjectRel.class,
            joinLeft = "empId", joinRight = "projectId")
    private List<Long> projectIds;

    @Field(label = "Employee Photo", fieldType = FieldType.FILE)
    private FileInfo photo;

    @Field(label = "Employee Documents", fieldType = FieldType.MULTI_FILE)
    private List<FileInfo> documents;

    @Field(length = 256)
    private String description;

    @Field(label = "Tenant ID")
    private Long tenantId;
}
