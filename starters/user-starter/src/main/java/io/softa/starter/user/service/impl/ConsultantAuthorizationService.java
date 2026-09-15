package io.softa.starter.user.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.ConsultantAuthorization;

/**
 * Persistence for {@link ConsultantAuthorization}, kept package-private in intent: grants are only
 * ever read and written through {@link ConsultantServiceImpl}, which owns the rules that make a
 * grant mean something (the enabled switch, the calendar, minting the membership). A caller that
 * reached the rows directly would get dates without the rules — which is how "authorized" and
 * "actually able to enter" drift apart.
 */
@Service
public class ConsultantAuthorizationService
        extends EntityServiceImpl<ConsultantAuthorization, Long> {
}
