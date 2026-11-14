package io.softa.starter.flow.runtime.spi;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A person as the directory lists them, for picking who receives work, a copy or a delegation: an employee by the
 * login account approvals name them by, with what tells two people of one name apart. An account without an employee
 * record has its name only.
 */
@Schema(name = "DirectoryPerson")
public record DirectoryPerson(

        @Schema(description = "Login account id: what an approver, a CC recipient or a delegate is stored as")
        Long id,

        @Schema(description = "Full name; for an account without an employee record, its nickname or username")
        String name,

        @Schema(description = "Employee code")
        String code,

        @Schema(description = "Department name")
        String departmentName,

        @Schema(description = "Position name")
        String positionName,

        @Schema(description = "Whether the account signs in, and so can be handed work. Told by the designer and "
                + "operations directory alone (/flow/directory/people/all); null elsewhere, where only people who can "
                + "act are offered and who cannot is not the caller's to learn")
        Boolean active
) {

    /** This person without saying whether they can act: as the directory answers anyone but a designer or an operator. */
    public DirectoryPerson withoutActive() {
        return active == null ? this : new DirectoryPerson(id, name, code, departmentName, positionName, null);
    }
}
