package io.softa.starter.user.service;

import java.util.Optional;

/**
 * SPI: an application's own rules on what a role may be saved as.
 *
 * <p>The framework judges a role save on what it can know — the grant ceiling (a non-admin hands out
 * only what they hold), the company and department grant shapes. Some rules belong to the application:
 * that a role able to change a certain kind of record must also say which companies it reaches, say.
 * Implement this as a Spring bean to refuse such a save with a sentence the person saving can act on.
 *
 * <p>Called by the role wizard ({@code POST /Role/wizard}, {@code PUT /Role/{id}/wizard}) once every
 * grant of the save has been written, inside the save's transaction, so the implementation reads the
 * role as it is about to be committed — its navigations and permissions, its data scopes, its company
 * and department grants — through the ordinary services, and a refusal rolls the whole save back.
 * Validators run in {@code @Order}; the first refusal wins. Every caller is held to them, administrators
 * included: a rule about what a role must look like is not a rule about who may write it.
 *
 * <p>Generic writes to the role models bypass the wizard and are refused to everyone but
 * administrators (see {@code SystemRoleWriteGuard}); they do not consult this.
 */
@FunctionalInterface
public interface RoleSaveValidator {

    /**
     * @param roleId  the role just saved
     * @param created whether the save created it
     * @return why the save is refused, worded for the person saving; empty to let it through
     */
    Optional<String> validate(Long roleId, boolean created);
}
