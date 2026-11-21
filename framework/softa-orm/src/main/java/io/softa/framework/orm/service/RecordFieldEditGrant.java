package io.softa.framework.orm.service;

import java.io.Serializable;

/**
 * Whether the current user may write one field of one record by something other than their own permissions on the
 * model — an approval step that lets its approver edit the field, say. Asked where such a write is prepared on its
 * own, ahead of the action that saves it: a file uploaded to a field, before the approval writes it back.
 *
 * <p>Any implementation that grants is enough; none, and the user's permissions on the model decide alone.
 */
public interface RecordFieldEditGrant {

    /**
     * @param modelName the record's model
     * @param rowId     the record's id
     * @param fieldName the field to be written
     * @return whether this grant lets the current user write it now
     */
    boolean grants(String modelName, Serializable rowId, String fieldName);
}
