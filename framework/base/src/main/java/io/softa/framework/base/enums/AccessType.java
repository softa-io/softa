package io.softa.framework.base.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Data access type
 */
@AllArgsConstructor
@Getter
public enum AccessType {
    READ,
    UPDATE,
    CREATE,
    DELETE
}
