package io.softa.framework.orm.utils;

import java.util.concurrent.atomic.AtomicLong;
import me.ahoo.cosid.IdGenerator;

/**
 * Mock IdGenerator for testing purposes.
 */
public class MockIdGenerator implements IdGenerator {

    public static final MockIdGenerator INSTANCE = new MockIdGenerator();

    private final AtomicLong counter = new AtomicLong(1000000L);

    private MockIdGenerator() {
    }

    @Override
    public long generate() {
        return counter.incrementAndGet();
    }
}
