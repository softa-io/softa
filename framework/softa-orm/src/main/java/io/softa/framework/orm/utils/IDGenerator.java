package io.softa.framework.orm.utils;

import me.ahoo.cosid.IdGenerator;
import me.ahoo.cosid.codec.Radix;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.SpringContextUtils;

/**
 * Distributed id generator based on CosId.
 * <p>
 * CosId 5 keeps no global generator: the generator named {@code default} ({@code cosid.generators.default})
 * is the primary {@link IdGenerator} bean of the context. It is looked up on first use and forgotten
 * when the context closes, so a closed context never mints ids.
 */
@Component
public class IDGenerator implements DisposableBean {

    /** Base36 / base62 text without padding, as CosId 3's Radix36IdConverter / Radix62IdConverter wrote it. */
    private static final Radix BASE36 = Radix.of(36, Radix.maxWidth(36), false);
    private static final Radix BASE62 = Radix.of(62, Radix.maxWidth(62), false);

    private static volatile IdGenerator generator;

    /**
     * Get the IdGenerator instance by lazy loading.
     */
    private static IdGenerator getGenerator() {
        IdGenerator current = generator;
        if (current == null) {
            synchronized (IDGenerator.class) {
                current = generator;
                if (current == null) {
                    if (!SpringContextUtils.existApplicationContext()) {
                        throw new IllegalStateException("CosId is not started: no Spring application context.");
                    }
                    try {
                        current = SpringContextUtils.getBeanByClass(IdGenerator.class);
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("CosId has no default generator. "
                                + "Declare cosid.generators.default in application.yml.", e);
                    }
                    generator = current;
                }
            }
        }
        return current;
    }

    /**
     * Use the given generator instead of the context's default one, e.g. a deterministic generator in unit
     * tests; {@code null} goes back to looking up the context's.
     *
     * @param idGenerator the generator to use
     */
    public static void use(IdGenerator idGenerator) {
        generator = idGenerator;
    }

    @Override
    public void destroy() {
        generator = null;
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
        return BASE36.encode(generateLongId());
    }

    /**
     * Generate distributed string id in base62.
     *
     * @return distributed string id
     */
    public static String generateStringIdBase62() {
        return BASE62.encode(generateLongId());
    }
}
