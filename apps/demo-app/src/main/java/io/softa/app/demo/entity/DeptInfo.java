package io.softa.app.demo.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.TimelineModel;

/**
 * DeptInfo Model — the timeline (effective-dated) exemplar: each department keeps
 * contiguous slices over time (org-chart as of a date), queried by
 * {@code Context.effectiveDate} and referenced by the logical {@code id}
 * ({@code EmpInfo.deptId} with {@code onDelete = RESTRICT} fires on entity deletion).
 * Timeline models require an app-generated logical id (the auto-increment lands on
 * {@code sliceId}), hence {@code DISTRIBUTED_LONG}.
 */
@Data
@Model(label = "Department", businessKey = {"code"}, timeline = true,
        idStrategy = IdStrategy.DISTRIBUTED_LONG)
// Backstop + as-of covering index: the interval maintainer is check-then-act, so a true
// write race on one entity surfaces as a unique violation instead of silent same-start
// slices; explicit indexName keeps within the 60-char global limit.
@Index(indexName = "uk_dept_info_timeline",
        fields = {"id", "effectiveStartDate", "effectiveEndDate"}, unique = true)
@EqualsAndHashCode(callSuper = true)
public class DeptInfo extends TimelineModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true, length = 100)
    private String name;

    @Field(copyable = false)
    private String code;

    @Field(label = "Employees", fieldType = FieldType.ONE_TO_MANY, relatedField = "deptId")
    private List<EmpInfo> empIds;

    @Field(length = 256)
    private String description;

    @Field
    private Boolean active;
}
