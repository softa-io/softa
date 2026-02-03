package io.softa.framework.orm.changelog;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.TimeConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.AccessType;
import io.softa.framework.orm.changelog.event.TransactionEvent;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ListUtils;

/**
 * Default implementation of ChangeLogPublisher.
 * Publishes ChangeLog events to the ApplicationEventPublisher and stores logs
 * in ChangeLogHolder
 * only if `system.enable-change-log` is true in the configuration.
 */
@Slf4j
@Service
public class ChangeLogPublisherImpl implements ChangeLogPublisher {

    private static final TransactionEvent CHANGE_LOG_EVENT = new TransactionEvent();

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * Save changeLogs to transaction-bound buffer and publish ChangeLog event (only publish
     * once in a transaction).
     * This operation is performed only if change log is enabled in the system
     * configuration.
     * If the transaction-bound list is not empty, append the ChangeLog list.
     *
     * @param changeLogs ChangeLog list
     */
    private void publish(List<ChangeLog> changeLogs) {
        if (changeLogs == null || changeLogs.isEmpty()) {
            return;
        }
        boolean publishEvent = ChangeLogHolder.isEmpty();
        ChangeLogHolder.add(changeLogs);
        if (publishEvent) {
            // Publish event only when adding the first batch in the transaction
            applicationEventPublisher.publishEvent(CHANGE_LOG_EVENT);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishCreationLog(String model, List<Map<String, Object>> createdRows, LocalDateTime createdTime) {
        // Skip if disabled or no rows
        if (!SystemConfig.env.isEnableChangeLog() || createdRows == null || createdRows.isEmpty()) {
            return;
        }
        String primaryKey = ModelManager.getModelPrimaryKey(model);
        List<ChangeLog> changeLogs = createdRows.stream().map(row -> {
            Serializable pKey = (Serializable) row.get(primaryKey);
            ChangeLog changeLog = generateChangeLog(model, AccessType.CREATE, pKey, createdTime);
            // For creation log, dataAfterChange contains the full created row
            changeLog.setDataAfterChange(row);
            return changeLog;
        }).collect(Collectors.toList());
        this.publish(changeLogs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishUpdateLog(String model, List<Map<String, Object>> updatedRows,
            Map<Serializable, Map<String, Object>> originalRowsMap, LocalDateTime updatedTime) {
        // Skip if disabled or no rows
        if (!SystemConfig.env.isEnableChangeLog() || updatedRows == null || updatedRows.isEmpty()) {
            return;
        }
        String primaryKey = ModelManager.getModelPrimaryKey(model);
        // Deep copy to avoid modifying the input list when removing fields
        List<Map<String, Object>> rowsToLog = ListUtils.deepCopy(updatedRows);
        List<ChangeLog> changeLogs = rowsToLog.stream().map(row -> {
            Serializable pKey = (Serializable) row.get(primaryKey);
            // Remove primary key and audit fields from the dataAfterChange map for update
            // logs
            row.remove(primaryKey);
            row.remove(ModelConstant.ID); // ID might be different from PK
            ModelManager.getModel(model).getAuditUpdateFields().forEach(row::remove);

            ChangeLog changeLog = generateChangeLog(model, AccessType.UPDATE, pKey, updatedTime);
            changeLog.setDataBeforeChange(originalRowsMap.get(pKey));
            // dataAfterChange contains only the changed fields (excluding PK and audit
            // fields)
            changeLog.setDataAfterChange(row);
            return changeLog;
        }).collect(Collectors.toList());
        this.publish(changeLogs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishDeletionLog(String model, List<Map<String, Object>> deletedRows, LocalDateTime deleteTime) {
        // Skip if disabled or no rows
        if (!SystemConfig.env.isEnableChangeLog() || deletedRows == null || deletedRows.isEmpty()) {
            return;
        }
        String primaryKey = ModelManager.getModelPrimaryKey(model);
        List<ChangeLog> changeLogs = deletedRows.stream().map(row -> {
            Serializable pKey = (Serializable) row.get(primaryKey);
            ChangeLog changeLog = generateChangeLog(model, AccessType.DELETE, pKey, deleteTime);
            // For deletion log, dataBeforeChange contains the full deleted row
            changeLog.setDataBeforeChange(row);
            return changeLog;
        }).collect(Collectors.toList());
        this.publish(changeLogs);
    }

    /**
     * Generate a ChangeLog object with context information.
     *
     * @param model       model name
     * @param accessType  access type
     * @param id          id of the data row
     * @param updatedTime the time the change occurred
     * @return Populated ChangeLog object
     */
    private ChangeLog generateChangeLog(String model, AccessType accessType, Serializable id,
            @NotNull LocalDateTime updatedTime) {
        Context context = ContextHolder.getContext();
        ChangeLog changeLog = new ChangeLog();
        changeLog.setTraceId(context.getTraceId());
        changeLog.setModel(model);
        changeLog.setRowId(id);
        changeLog.setAccessType(accessType);
        changeLog.setTenantId(context.getTenantId());
        // Set changeLog audit fields from context
        changeLog.setChangedById(context.getUserId());
        changeLog.setChangedBy(context.getName());
        changeLog.setChangedTime(updatedTime.format(TimeConstant.DATETIME_FORMATTER));
        return changeLog;
    }
}