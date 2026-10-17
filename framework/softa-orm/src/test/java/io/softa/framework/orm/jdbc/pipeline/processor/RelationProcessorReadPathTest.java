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
                        + "they searched for: a label goes through searchListIgnoringRowScope, anything "
                        + "more through searchRelatedRows, which decides the row range by the related model")
                .isEmpty();
    }

    /**
     * A subQuery names its own fields, filters and nested subQueries. Read across the caller's row range
     * it hands out any field of any row the caller is merely shown a reference to — dates of birth of
     * employees in departments the caller cannot open, reached through a colleague's direct reports.
     */
    @Test
    void expandingProcessorsReadSubQueriesThroughTheRelatedRowsRead() {
        for (Class<?> processor : EXPANDING_PROCESSORS) {
            assertThat(referencesUtf8(processor, "searchRelatedRows"))
                    .as("%s expands a subQuery and must read it through searchRelatedRows",
                            processor.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    void expandingProcessorsDoCallTheRowScopeCrossingRead() {
        // OneToMany reaches its labels through searchName; it crosses the range for the patch diff only.
        for (Class<?> processor : EXPANDING_PROCESSORS) {
            assertThat(referencesUtf8(processor, "searchListIgnoringRowScope"))
                    .as("%s expands a relation and must reach its rows through the crossing read",
                            processor.getSimpleName())
                    .isTrue();
        }
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
