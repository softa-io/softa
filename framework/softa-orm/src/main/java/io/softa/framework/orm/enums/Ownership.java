package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Data-ownership tier tag — which channel controls a row and how it evolves.
 *
 * <p><b>Not yet referenced by any {@code @Field}.</b> The metadata catalog
 * ({@code sys_*}) previously carried this tag as a <i>column</i> to partition
 * code-authored rows from Studio no-code rows; that partition has been retired
 * — the annotation and Studio lanes now reconcile the same rows purely by
 * business key (modelName / fieldName / optionSetCode / itemCode +
 * {@code renamedFrom}), so no {@code sys_*} column records ownership.
 *
 * <p>The enum is published as a metadata {@link OptionSet} (materialized into
 * {@code sys_option_set} / {@code sys_option_item}, optionSetCode
 * {@code Ownership}) so future business-data scenarios that ship
 * platform-defaults a tenant may customize (e.g. system roles, workflow
 * templates, email templates, default categories) can reference the tier as an
 * option field.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum Ownership {

    /** Platform-version-controlled; materialized from code annotations. */
    PLATFORM_MAINTAINED("Platform Maintained"),

    /** Platform no-code definition, version-managed by Studio. */
    STUDIO_MANAGED("Studio Managed"),

    /** Platform-provided default that tenants may override. */
    PLATFORM_DEFAULT("Platform Default"),

    /** Tenant-owned data. */
    TENANT("Tenant");

    @JsonValue
    private final String code;
}