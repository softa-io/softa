package io.softa.framework.base.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code record} as a named set of template variables: the values a piece of code hands to
 * a template that has no model to read them from — a verification code, an invitation link, an
 * approval notice.
 *
 * <p>A template names the set it is written against ({@code variableSetName}); the editor then offers
 * exactly these fields, a save refuses a variable the set does not have, and a send refuses an object
 * of another set. The set itself knows nothing of the templates that use it.
 *
 * <p>Each record component is one variable. Its label, description and sample value come from the
 * component's {@code @Schema} ({@code title}, {@code description}, {@code example}); a component
 * without a title is labelled by its humanized name. A component may be text, a number, a boolean,
 * a date or time, an enum, another record (read as {@code outer.inner}), or a list of records (a
 * loop).
 *
 * <p>Declared sets are collected at startup into
 * {@code io.softa.framework.orm.meta.TemplateVariablesRegistry}; nothing is stored in the database.
 * Two classes declaring the same {@link #name()} stop the application from starting.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TemplateVariables {

    /**
     * The set's name, as a template stores it. Written like a template code, with the owning module
     * first: {@code flow.approval-notice}, {@code user.verification-code}.
     */
    String name();

    /** Display label; empty = the humanized last segment of {@link #name()}. */
    String label() default "";
}
