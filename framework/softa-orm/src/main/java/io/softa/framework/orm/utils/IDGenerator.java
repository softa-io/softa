package io.softa.framework.orm.utils;

import lombok.NoArgsConstructor;
import me.ahoo.cosid.IdGenerator;
import me.ahoo.cosid.converter.Radix36IdConverter;
import me.ahoo.cosid.converter.Radix62IdConverter;
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider;
import org.springframework.stereotype.Component;

/**
 * Distributed id generator based on CosId.
 */
@Component
@NoArgsConstructor
public class IDGenerator {

    private static volatile IdGenerator generator;

    /**
     * Get IdGenerator instance by lazy loading.
     */
    private static IdGenerator getGenerator() {
        if (generator == null) {
            synchronized (IDGenerator.class) {
                if (generator == null) {
                    // Try to get the default IdGenerator from CosId provider
                    IdGenerator share = DefaultIdGeneratorProvider.INSTANCE.getShare();
                    if (share == null) {
                        throw new IllegalStateException("CosId ShareIdGenerator is null. Please check your application.yml configuration for CosId.");
                    }
                    generator = share;
                }
            }
        }
        return generator;
    }

    /**
     * Generate distributed long id.
     *
     * @return distributed long id
     */
    public static Long generateLongId() {
        return getGenerator().generate();
    }

    /**
     * Generate distributed string id, base36 by default.
     *
     * @return distributed string id
     */
    public static String generateStringId() {
        return generateStringIdBase36();
    }

    /**
     * Generate distributed string id in base36.
     *
     * @return distributed string id
     */
    public static String generateStringIdBase36() {
        return Radix36IdConverter.INSTANCE.asString(generateLongId());
    }

    /**
     * Generate distributed string id in base62.
     *
     * @return distributed string id
     */
    public static String generateStringIdBase62() {
        return Radix62IdConverter.INSTANCE.asString(generateLongId());
    }
}
