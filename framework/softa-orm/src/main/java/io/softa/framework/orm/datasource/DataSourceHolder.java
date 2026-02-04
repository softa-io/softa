package io.softa.framework.orm.datasource;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Data source context holder.
 */
public class DataSourceHolder {

    private static final ScopedValue<String> DATA_SOURCE_KEY = ScopedValue.newInstance();

    /**
     * Get the key of current datasource.
     */
    public static String getDataSourceKey() {
        try {
            return DATA_SOURCE_KEY.get();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    /**
     * Run the action with the given datasource key in a new virtual thread scope.
     * @param key datasource key
     * @param runnable action to run
     */
    public static void runWithDataSource(String key, Runnable runnable) {
        Objects.requireNonNull(key, "Datasource key must not be null");
        ScopedValue.where(DATA_SOURCE_KEY, key).run(runnable);
    }

    /**
     * Call the operation with the given datasource key in a new virtual thread scope.
     * @param key datasource key
     * @param op operation to call
     * @return operation result
     * @throws X if the operation throws
     */
    public static <T, X extends Throwable> T callWithDataSource(String key, ScopedValue.CallableOp<T, X> op) throws X {
        Objects.requireNonNull(key, "Datasource key must not be null");
        return ScopedValue.where(DATA_SOURCE_KEY, key).call(op);
    }
}
