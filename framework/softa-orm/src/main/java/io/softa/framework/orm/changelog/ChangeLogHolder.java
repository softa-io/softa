package io.softa.framework.orm.changelog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.orm.changelog.message.dto.ChangeLog;

/**
 * Transaction-bound buffer for ChangeLog, used to collect multiple changes within a single transaction.
 */
public class ChangeLogHolder {

    private ChangeLogHolder() {}

    private static final Object RESOURCE_KEY = new Object();

    /**
     * Return true if there is no current transaction-bound ChangeLog list, or it is empty.
     */
    public static boolean isEmpty() {
        List<ChangeLog> list = get();
        return list == null || list.isEmpty();
    }

    /**
     * Get the current transaction-bound ChangeLog list, or null if none exists.
     */
    @SuppressWarnings("unchecked")
    public static List<ChangeLog> get() {
        return (List<ChangeLog>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
    }

    /**
     * Get the current list or create and bind a new list to the transaction.
     */
    public static List<ChangeLog> getOrCreate() {
        List<ChangeLog> current = get();
        if (current == null) {
            current = new ArrayList<>();
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, current);
        }
        return current;
    }

    /**
     * Append change logs into the current transaction-bound list.
     */
    public static void add(List<ChangeLog> changeLogs) {
        Objects.requireNonNull(changeLogs, "changeLogs must not be null");
        getOrCreate().addAll(changeLogs);
    }

    /**
     * Clear the transaction-bound ChangeLog list.
     */
    public static void clear() {
        TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
    }
}
