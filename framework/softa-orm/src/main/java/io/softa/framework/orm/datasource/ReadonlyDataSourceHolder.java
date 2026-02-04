package io.softa.framework.orm.datasource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Readonly datasource holder.
 * Used to randomly get a readonly datasource key.
 */
public class ReadonlyDataSourceHolder {

    private ReadonlyDataSourceHolder() {}

    private static volatile List<String> keys = List.of();

    /**
     * Add a readonly datasource key.
     *
     * @param key datasource key
     */
    public static void addReadonlyDataSourceKey(String key) {
        Objects.requireNonNull(key, "Readonly datasource key must not be null");
        synchronized (ReadonlyDataSourceHolder.class) {
            List<String> newKeys = new ArrayList<>(keys);
            if (!newKeys.contains(key)) {
                newKeys.add(key);
                keys = List.copyOf(newKeys);
            }
        }
    }

    /**
     * Get a readonly datasource key randomly.
     *
     * @return readonly datasource key
     */
    public static String getReadonlyDataSourceKey() {
        List<String> snapshot = keys;
        if (snapshot.isEmpty()) {
            return null;
        }
        int index = ThreadLocalRandom.current().nextInt(snapshot.size());
        return snapshot.get(index);
    }
}
