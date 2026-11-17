package io.softa.starter.flow.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.enums.ApprovalStatus;

/**
 * Base class of a model whose records are approved by flows: the approval fields every such record
 * carries, the same on every model. Extend it instead of {@link AuditableModel} and implement the model's
 * {@code ModelApprovalHandler}.
 *
 * <p>Like the audit fields, these carry {@code @Field} here once — the scanner walks the superclass chain.
 * They are the framework's to write: {@code approvalStatus} as the record's approval moves, the submitter
 * and the time on every submission. They are not {@code readonly}, which would keep the framework's own
 * writes out too; {@code ApprovalLockValidator} refuses them to everyone else, and freezes the
 * record while it is in approval and once it is approved. A copy is a new draft: none of them is copied.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class ApprovalModel extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String APPROVAL_STATUS = LambdaUtils.getAttributeName(ApprovalModel::getApprovalStatus);
    public static final String SUBMITTER_ID = LambdaUtils.getAttributeName(ApprovalModel::getSubmitterId);
    public static final String SUBMITTED_TIME = LambdaUtils.getAttributeName(ApprovalModel::getSubmittedTime);

    /** The fields only the framework writes. */
    public static final List<String> APPROVAL_FIELDS = List.of(APPROVAL_STATUS, SUBMITTER_ID, SUBMITTED_TIME);

    /**
     * Starts as {@code Draft}. Not {@code required}: the create pipeline checks required before it fills
     * the default, so the two together would make the default unreachable.
     */
    @Field(defaultValue = "Draft", copyable = false)
    protected ApprovalStatus approvalStatus;

    /** The user who submitted the record last: its latest approval's initiator. */
    @Field(label = "Submitter", copyable = false)
    protected Long submitterId;

    /** When the record was submitted last. */
    @Field(copyable = false)
    protected LocalDateTime submittedTime;

    /**
     * Whether {@code row}, a record of {@code modelName} as a read by its id returns it, was deleted softly. A model
     * that deletes softly keeps the row, flagged, and a read by id still returns it — {@code ModelService.getByIds}
     * leaves the soft-delete filter to queries. To its approvals such a record is gone, as one deleted for good is:
     * it is not submitted, cancelled or written back, and the lists say it was deleted.
     */
    public static boolean isDeleted(String modelName, Map<String, Object> row) {
        // The flag first: a row read without it, as most are, never asks the metadata.
        return row != null && Boolean.TRUE.equals(row.get(ModelConstant.SOFT_DELETED_FIELD))
                && ModelManager.isSoftDeleted(modelName);
    }
}
