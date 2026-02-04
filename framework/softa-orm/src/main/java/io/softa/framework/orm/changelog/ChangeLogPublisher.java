package io.softa.framework.orm.changelog;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Interface for publishing ChangeLog events.
 * Implementations can decide how and when to publish the logs.
 */
public interface ChangeLogPublisher {

    /**
     * Publish creation changeLog.
     *
     * @param model       model name
     * @param createdRows created data list
     * @param createdTime created time
     */
    void publishCreationLog(String model, List<Map<String, Object>> createdRows, LocalDateTime createdTime);

    /**
     * Publish update changeLog.
     *
     * @param model           model name
     * @param updatedRows     changed data list
     * @param originalRowsMap original data map, with id as key
     * @param updatedTime     updated time
     */
    void publishUpdateLog(String model, List<Map<String, Object>> updatedRows,
            Map<Serializable, Map<String, Object>> originalRowsMap, LocalDateTime updatedTime);

    /**
     * Publish deletion changeLog.
     *
     * @param model       model name
     * @param deletedRows deleted data list
     * @param deleteTime  deleted time
     */
    void publishDeletionLog(String model, List<Map<String, Object>> deletedRows, LocalDateTime deleteTime);

}