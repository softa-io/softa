package io.softa.starter.flow.runtime.spi;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A department as the directory lists it, for picking the departments a flow names: with its code and its parent,
 * since department names repeat between branches.
 */
@Schema(name = "DirectoryDepartment")
public record DirectoryDepartment(

        @Schema(description = "Department id")
        Long id,

        @Schema(description = "Department name")
        String name,

        @Schema(description = "Department code")
        String code,

        @Schema(description = "Parent department name")
        String parentName
) {
}
