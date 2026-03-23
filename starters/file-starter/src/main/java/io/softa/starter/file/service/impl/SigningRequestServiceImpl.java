package io.softa.starter.file.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.file.entity.SigningRequest;
import io.softa.starter.file.service.SigningRequestService;

/**
 * SigningRequest Model Service Implementation
 */
@Service
public class SigningRequestServiceImpl extends EntityServiceImpl<SigningRequest, Long> implements SigningRequestService {

}