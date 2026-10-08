package io.softa.framework.orm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * Data access type
 */
@AllArgsConstructor
@Getter
@OptionSet
public enum AccessType {
    READ,
    UPDATE,
    CREATE,
    DELETE,
    /** Reading rows out of the system in bulk. Granted on its own, so its row scope is its own too. */
    EXPORT
}
