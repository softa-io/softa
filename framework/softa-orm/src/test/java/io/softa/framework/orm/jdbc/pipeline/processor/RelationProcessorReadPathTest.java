package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which read the relation processors reach related rows through.
 *
 * <p>The behavioural tests all exercise the method itself, so none of them notices a processor wired
 * to the wrong one — a new processor added to the read pipeline, or an existing call quietly changed
 * back. That is the shape the original leak had: nobody wrote "let these rows through unguarded", it
 * came from the layer above, and no test was looking at the wiring.
 *
 * <p>Reads the compiled constant pool rather than the source, so it sees what the class actually
 * calls. A UTF8 constant is length-prefixed, which is what separates {@code searchList} from
 * {@code searchListIgnoringRowScope} — a plain substring search would match both.
 */
class RelationProcessorReadPathTest {

    /** Processors that re-read OTHER models while expanding a relation. */
    private static final List<Class<?>> EXPANDING_PROCESSORS = List.of(
            OneToManyProcessor.class, ManyToManyProcessor.class, XToOneGroupProcessor.class);

    @Test
    void expandingProcessorsDoNotCallThePlainSearchList() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> processor : EXPANDING_PROCESSORS) {
            if (referencesUtf8(processor, "searchList")) {
                offenders.add(processor.getSimpleName());
            }
        }

        assertThat(offenders)
                .as("a relation expansion reaches rows the caller was shown a reference to, not rows "
                        + "they searched for: it must call searchListIgnoringRowScope, which crosses "
                        + "the row range while the field guards stay on")
                .isEmpty();
    }

    @Test
    void expandingProcessorsDoCallTheRowScopeCrossingRead() {
        for (Class<?> processor : EXPANDING_PROCESSORS) {
            assertThat(referencesUtf8(processor, "searchListIgnoringRowScope"))
                    .as("%s expands a relation and must reach its rows through the crossing read",
                            processor.getSimpleName())
                    .isTrue();
        }
    }

    /**
     * The name search has the same split as the list search: the plain one keeps the caller's row
     * range, because it is what the picker endpoints call. A processor naming related rows through it
     * would blank the labels of rows the caller was shown a reference to.
     */
    @Test
    void expandingProcessorsDoNotCallThePlainSearchName() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> processor : EXPANDING_PROCESSORS) {
            if (referencesUtf8(processor, "searchName")) {
                offenders.add(processor.getSimpleName());
            }
        }

        assertThat(offenders)
                .as("a relation expansion names rows the caller was shown a reference to: it must call "
                        + "searchNameIgnoringRowScope, not the row-scoped searchName the pickers use")
                .isEmpty();
    }

    /**
     * True when the class file's constant pool holds this exact UTF8 entry.
     *
     * <p>Matches on the length prefix plus the bytes, so {@code searchList} does not match the
     * {@code searchListIgnoringRowScope} entry that shares its prefix.
     */
    private static boolean referencesUtf8(Class<?> type, String name) {
        byte[] classBytes = read(type);
        byte[] needle = new byte[name.length() + 2];
        needle[0] = (byte) (name.length() >>> 8);
        needle[1] = (byte) name.length();
        System.arraycopy(name.getBytes(StandardCharsets.UTF_8), 0, needle, 2, name.length());
        return indexOf(classBytes, needle) >= 0;
    }

    private static byte[] read(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("class file not on the test classpath: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
