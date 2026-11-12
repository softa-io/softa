package io.softa.starter.metadata.scanner;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CatalogBootCheck}: the rows the scanner would write, put through the boot checks.
 */
class CatalogBootCheckTest {

    /** Parses — the field it searches by exists — and is refused at boot: the shape of 3.0.1. */
    @Model(searchName = {"sentAt"})
    @SuppressWarnings("unused")
    static class Dispatch extends AuditableModel {
        @Field(label = "ID")
        private Long id;

        @Field
        private String subject;

        @Field
        private LocalDateTime sentAt;

        @Override
        public Serializable getId() {
            return id;
        }
    }

    @Model(searchName = {"subject"})
    @SuppressWarnings("unused")
    static class Notice extends AuditableModel {
        @Field(label = "ID")
        private Long id;

        @Field
        private String subject;

        @Override
        public Serializable getId() {
            return id;
        }
    }

    @Test
    void aModelTheBootWouldRefuseFailsTheCheck() {
        assertThatThrownBy(() -> CatalogBootCheck.validate(List.of(Dispatch.class), List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model Dispatch field sentAt");
    }

    @Test
    void aSoundModelPassesAndTheConfigIsPutBack() {
        SystemConfig before = SystemConfig.env;

        CatalogBootCheck.validate(List.of(Notice.class), List.of(), true);

        assertThat(SystemConfig.env).isSameAs(before);
    }

    /** A wrong package would otherwise check nothing and pass. */
    @Test
    void packagesWithoutAModelAreAFailureNotAPass() {
        assertThatThrownBy(() -> CatalogBootCheck.validate(List.of("io.softa.no.such.package"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No @Model class");
    }
}
