package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;

/**
 * The fields of a company that the framework itself reads — not a model.
 *
 * <p>The application declares the {@code Company} model by extending this class and adds whatever
 * else a company carries in its domain. The model cannot be declared here: a model has exactly one
 * declaring class, and an application's company typically references its own types (an employee as
 * the authorised signatory, a domain enum), which a framework class cannot name. The framework does
 * not need to own the model anyway — every place it reads a company goes through
 * {@code ModelConstant.COMPANY_MODEL} and degrades when no such model exists.
 *
 * <p>What it does need is for the fields it reads by name to exist with the meaning it assumes.
 * Declaring them here is what makes that a contract instead of a hope: {@code country} drives the
 * company's country context, {@code timeZone} is the calendar its dates are judged on, {@code abbreviation}
 * is what the {@code companyAbbr} sequence token renders, {@code name} and {@code active} pick the company a
 * template preview is shown for. A field
 * the framework does not read stays on the application's class; moving one here later is compatible.
 *
 * <p>The scanner walks the superclass chain, so these are emitted as fields of the subclass's model
 * exactly as if the subclass declared them.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class BaseCompany extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Filled from the {@code Company.code} sequence when left blank. */
    @Field(label = "Company Code", length = 32, autoSequence = true)
    private String code;

    @Field(label = "Company Name", required = true, length = 128)
    private String name;

    /**
     * ISO 3166-1 alpha-2 (code-as-id) — the key every country-specific rule reads.
     *
     * <p>Referenced by name: this module sees the reference-data starter only on its test classpath.
     */
    @Field(label = "Country/Region", required = true, fieldType = FieldType.MANY_TO_ONE,
            relatedModelName = "CountryRegion")
    private String country;

    /**
     * A short form of the name, for codes that carry a company prefix: a sequence template containing
     * {@code {companyAbbr}} renders it in place, so {@code {companyAbbr}{seq:7}} gives {@code ZK1000021}
     * for a company abbreviated {@code ZK}.
     *
     * <p>Optional, and not unique — two companies may share one; the counter behind a sequence is shared
     * across companies, so codes stay unique regardless. Changing it affects only codes generated after.
     *
     * <p>Only the character rule carries a message of its own. A field has a single constraint message,
     * and an over-long value is caught by {@code length} with the framework's generic wording.
     */
    @Field(label = "Company Abbreviation", length = 10, pattern = "^[A-Za-z0-9_-]*$",
            constraintMessage = "Company Abbreviation can only contain letters, numbers, - and _.",
            description = "Short form of the company name, e.g. ZK. Numbering rules that include "
                    + "{companyAbbr} put it into the code, e.g. ZK1000021.")
    private String abbreviation;

    /**
     * The company's IANA time zone ({@code Asia/Singapore}) — the calendar its own dates are judged on, read by
     * {@code CompanyClock}. A company's today is not the server's: a server in another zone answers with a
     * different date for hours every day.
     *
     * <p>A reference to the reference-data {@code CountryTimeZone}, whose id is the zone id itself, so the column
     * holds the plain zone string. Referenced by name: this module sees the reference-data starter only on its
     * test classpath. Left empty on create, it takes the country's default zone.
     */
    @Field(label = "Time Zone", fieldType = FieldType.MANY_TO_ONE, relatedModelName = "CountryTimeZone",
            length = 32)
    private String timeZone;

    @Field(label = "Active", defaultValue = "true")
    private Boolean active;
}
