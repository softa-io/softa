package io.softa.framework.orm.jdbc;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.ModelManager;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tool class for filling in the audit fields of the data.
 */
public class AutofillFields {

    /**
     * Get the audit field values for inserting data.
     *
     * @param modelName model name
     * @param insertTime Insert time
     * @return Audit field values to be filled
     */
    private static Map<String, Object> getInsertAudit(String modelName, LocalDateTime insertTime) {
        String userId = ContextHolder.getContext().getUserId();
        String name = ContextHolder.getContext().getName();
        Map<String, Object> result = new HashMap<>();
        Set<String> auditCreateFields = ModelManager.getModel(modelName).getAuditCreateFields();
        if (auditCreateFields.contains(ModelConstant.CREATED_ID)) {
            result.put(ModelConstant.CREATED_ID, userId);
        }
        if (auditCreateFields.contains(ModelConstant.CREATED_BY)) {
            result.put(ModelConstant.CREATED_BY, name);
        }
        if (auditCreateFields.contains(ModelConstant.CREATED_TIME)) {
            result.put(ModelConstant.CREATED_TIME, insertTime);
        }
        result.putAll(getUpdateAudit(modelName, insertTime));
        return result;
    }

    /**
     * Get the audit field values for updating data.
     *
     * @param modelName model name
     * @param updatedTime Update time
     * @return Audit field values to be filled
     */
    private static Map<String, Object> getUpdateAudit(String modelName, LocalDateTime updatedTime) {
        String userId = ContextHolder.getContext().getUserId();
        String name = ContextHolder.getContext().getName();
        Map<String, Object> result = new HashMap<>();
        Set<String> auditUpdateFields = ModelManager.getModel(modelName).getAuditUpdateFields();
        if (auditUpdateFields.contains(ModelConstant.UPDATED_ID)) {
            result.put(ModelConstant.UPDATED_ID, userId);
        }
        if (auditUpdateFields.contains(ModelConstant.UPDATED_TIME)) {
            result.put(ModelConstant.UPDATED_TIME, updatedTime);
        }
        if (auditUpdateFields.contains(ModelConstant.UPDATED_BY)) {
            result.put(ModelConstant.UPDATED_BY, name);
        }
        return result;
    }

    /**
     * Batch fill in the audit fields when inserting.
     *
     * @param modelName model name
     * @param rows    List data
     * @param insertTime Insert time
     */
    public static void fillAuditFieldsForInsert(String modelName, List<Map<String, Object>> rows, LocalDateTime insertTime) {
        if (ContextHolder.getContext().isSkipAutoAudit()) {
            return;
        }
        Map<String, Object> insertAudit = getInsertAudit(modelName, insertTime);
        rows.forEach(row -> row.putAll(insertAudit));
    }

    /**
     * Batch fill in the audit fields when updating.
     *
     * @param modelName model name
     * @param rows     List data
     * @param updatedTime Update time
     */
    public static void fillAuditFieldsForUpdate(String modelName, List<Map<String, Object>> rows, LocalDateTime updatedTime) {
        if (ContextHolder.getContext().isSkipAutoAudit()) {
            return;
        }
        Map<String, Object> updateAudit = getUpdateAudit(modelName, updatedTime);
        rows.forEach(row -> row.putAll(updateAudit));
    }

    /**
     * Fill in the tenant ID when inserting data, using the tenant ID of the current user.
     *
     * @param modelName model name
     * @param rows List data
     */
    public static void fillTenantFieldForInsert(String modelName, List<Map<String, Object>> rows) {
        // Check if the model is multi-tenant
        if (ModelManager.isMultiTenant(modelName)) {
            String tenantId = ContextHolder.getContext().getTenantId();
            rows.forEach(row -> row.put(ModelConstant.TENANT_ID, tenantId));
        }
    }

}
