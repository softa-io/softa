package io.softa.framework.orm.changelog;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.base.constant.TimeConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.ListUtils;
import io.softa.framework.orm.changelog.event.TransactionEvent;
import io.softa.framework.orm.changelog.message.ChangeLogProducer;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * Default implementation of ChangeLogPublisher.
 * Builds the logs of models with {@code enableAuditLog} on, stores them in the transaction-bound
 * ChangeLogHolder and publishes a ChangeLog event once per transaction. flow-starter's synchronous
 * triggers read the buffer before commit whatever the configuration; sending it to MQ after commit
 * is what `system.enable-change-log` gates (see ChangeLogProducer). Without transaction
 * synchronization nothing is sent after commit, as before.
 */
@Slf4j
@Service
public class ChangeLogPublisherImpl implements ChangeLogPublisher {

    private static final TransactionEvent CHANGE_LOG_EVENT = new TransactionEvent();

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private ChangeLogProducer changeLogProducer;

    /**
     * Save changeLogs to the transaction-bound buffer. The first batch of a transaction also
     * registers the synchronization that sends the buffer after commit, and publishes the
     * ChangeLog event the {@code BEFORE_COMMIT} listeners run on.
     *
     * @param changeLogs ChangeLog list
     */
    private void publish(List<ChangeLog> changeLogs) {
        if (changeLogs == null || changeLogs.isEmpty()) {
            return;
        }
        boolean firstBatch = ChangeLogHolder.isEmpty();
        ChangeLogHolder.add(changeLogs);
        if (firstBatch) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new SendAfterCommit());
            }
            applicationEventPublisher.publishEvent(CHANGE_LOG_EVENT);
        }
    }

    /**
     * Sends the buffer after commit and clears it once the transaction completes, commit or
     * rollback. Both steps live in one synchronization because Spring runs AFTER_COMMIT and
     * AFTER_COMPLETION transactional listeners in the same step: as two listeners of equal order
     * they ran in whatever order reflection returned the methods, and a clear that ran first left
     * the send an empty buffer, losing every change log of the process.
     */
    private final class SendAfterCommit implements TransactionSynchronization {

        @Override
        public void afterCommit() {
            changeLogProducer.sendChangeLog(ChangeLogHolder.get());
        }

        @Override
        public void afterCompletion(int status) {
            ChangeLogHolder.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publishCreationLog(String model, List<Map<String, Object>> createdRows, LocalDateTime createdTime) {
        // Skip if disabled or no rows
        if (createdRows == null || createdRows.isEmpty() || !ModelManager.isAuditLogEnabled(model)) {
            return;
        }
        String primaryKey = ModelManager.getModelPrimaryKey(model);
        List<ChangeLog> changeLogs = createdRows.stream().map(row -> {
            Serializable pKey = (Serializable) row.get(primaryKey);
            ChangeLog changeLog = generateChangeLog(model, AccessType.CREATE, pKey, createdTime);
            // For creation log, dataAfterChange contains the full created row
            changeLog.setDataAfterChange(row);
            changeLog.setRefs(refsOf(model, row, null));
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
        if (updatedRows == null || updatedRows.isEmpty() || !ModelManager.isAuditLogEnabled(model)) {
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
            if (ModelManager.isVersionControl(model)) {
                // The optimistic-lock version is the framework's, like the audit fields, and the
                // value a written row carries is the version it was read at, not the one it got.
                row.remove(ModelConstant.VERSION);
            }

            ChangeLog changeLog = generateChangeLog(model, AccessType.UPDATE, pKey, updatedTime);
            changeLog.setDataBeforeChange(originalRowsMap.get(pKey));
            // dataAfterChange contains only the changed fields (excluding PK and audit
            // fields)
            changeLog.setDataAfterChange(row);
            changeLog.setRefs(refsOf(model, originalRowsMap.get(pKey), row));
            changeLog.setChangedFields(new ArrayList<>(row.keySet()));
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
        if (deletedRows == null || deletedRows.isEmpty() || !ModelManager.isAuditLogEnabled(model)) {
            return;
        }
        String primaryKey = ModelManager.getModelPrimaryKey(model);
        List<ChangeLog> changeLogs = deletedRows.stream().map(row -> {
            Serializable pKey = (Serializable) row.get(primaryKey);
            ChangeLog changeLog = generateChangeLog(model, AccessType.DELETE, pKey, deleteTime);
            // For deletion log, dataBeforeChange contains the full deleted row
            changeLog.setDataBeforeChange(row);
            changeLog.setRefs(refsOf(model, row, null));
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
    /**
     * {@code field=id} for every many-to-one value in the rows, for {@link ChangeLog#getRefs()}.
     *
     * <p>Only many-to-one: that is the side of a parent–child link the child row holds, and the
     * child row is the one whose history has to be found from its parent. Null when the model has
     * none, so a log that points at nothing carries no empty list.
     */
    static List<String> refsOf(String model, Map<String, Object> first, Map<String, Object> second) {
        Set<String> refs = new LinkedHashSet<>();
        for (MetaField field : ModelManager.getModelFields(model)) {
            if (!FieldType.MANY_TO_ONE.equals(field.getFieldType())) {
                continue;
            }
            addRef(refs, field.getFieldName(), first);
            addRef(refs, field.getFieldName(), second);
        }
        return refs.isEmpty() ? null : new ArrayList<>(refs);
    }

    private static void addRef(Set<String> refs, String fieldName, Map<String, Object> row) {
        if (row == null) {
            return;
        }
        Object value = row.get(fieldName);
        if (value instanceof Map<?, ?> reference) {
            value = reference.get(ModelConstant.ID);
        }
        if (value != null && !(value instanceof Collection<?>) && !String.valueOf(value).isEmpty()) {
            refs.add(fieldName + "=" + value);
        }
    }

    private ChangeLog generateChangeLog(String model, AccessType accessType, Serializable id,
            @NotNull LocalDateTime updatedTime) {
        Context context = ContextHolder.getContext();
        ChangeLog changeLog = new ChangeLog();
        changeLog.setTraceId(context.getTraceId());
        changeLog.setModel(model);
        changeLog.setRowId(String.valueOf(id));
        changeLog.setAccessType(accessType);
        changeLog.setTenantId(context.getTenantId());
        // Set changeLog audit fields from context
        changeLog.setChangedById(context.getUserId());
        changeLog.setChangedBy(context.getName());
        changeLog.setChangedTime(updatedTime.format(TimeConstant.DATETIME_FORMATTER));
        return changeLog;
    }
}