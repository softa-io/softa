package io.softa.starter.metadata.sequence.service;

import java.time.LocalDateTime;
import io.softa.framework.orm.sequence.exception.SequenceTemplateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRendererTest {

    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 4, 29, 14, 30);
    private static final String CODE = "Employee.code";

    private TemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TemplateRenderer();
    }

    @Test
    void seq_withoutPadding() {
        assertThat(renderer.render("EMP-{seq}", 42, ANCHOR, CODE)).isEqualTo("EMP-42");
    }

    @Test
    void seq_zeroPadded() {
        assertThat(renderer.render("EMP-{seq:5}", 42, ANCHOR, CODE)).isEqualTo("EMP-00042");
    }

    @Test
    void seq_paddingWiderThanNumber_keepsZeros() {
        assertThat(renderer.render("{seq:8}", 7, ANCHOR, CODE)).isEqualTo("00000007");
    }

    @Test
    void seq_paddingNarrowerThanNumber_doesNotTruncate() {
        // Format spec "%05d" with value 1234567 yields "1234567" — width is a minimum.
        assertThat(renderer.render("{seq:5}", 1234567L, ANCHOR, CODE)).isEqualTo("1234567");
    }

    @Test
    void dateTokens_renderFromAnchor() {
        assertThat(renderer.render("{yyyy}-{MM}-{dd}", 1, ANCHOR, CODE)).isEqualTo("2026-04-29");
        assertThat(renderer.render("{yy}{MM}", 1, ANCHOR, CODE)).isEqualTo("2604");
    }

    @Test
    void compositeTemplate() {
        assertThat(renderer.render("EMP-{yyyy}-{seq:5}", 42, ANCHOR, CODE))
                .isEqualTo("EMP-2026-00042");
    }

    @Test
    void literalChars_passThrough() {
        assertThat(renderer.render("/foo/{yyyy}/bar-{seq:3}.txt", 9, ANCHOR, CODE))
                .isEqualTo("/foo/2026/bar-009.txt");
    }

    @Test
    void emptyTemplate_throws() {
        assertThatThrownBy(() -> renderer.render("", 1, ANCHOR, CODE))
                .isInstanceOf(SequenceTemplateException.class)
                .hasMessageContaining("Template is empty");
    }

    @Test
    void unknownToken_throws() {
        assertThatThrownBy(() -> renderer.render("EMP-{foo}-{seq}", 1, ANCHOR, CODE))
                .isInstanceOf(SequenceTemplateException.class)
                .hasMessageContaining("Unknown template token");
    }

    @Test
    void noTokens_returnedAsIs() {
        // Templates without {seq} are caught at config-validation time; the renderer
        // itself just echoes the literal back without throwing.
        assertThat(renderer.render("plain", 1, ANCHOR, CODE)).isEqualTo("plain");
    }
}
