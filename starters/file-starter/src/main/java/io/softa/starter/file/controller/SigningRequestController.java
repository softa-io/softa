package io.softa.starter.file.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.file.entity.SigningRequest;
import io.softa.starter.file.service.SigningRequestService;

/**
 * SigningRequest Model Controller
 */
@Tag(name = "SigningRequest")
@RestController
@RequestMapping("/SigningRequest")
public class SigningRequestController extends EntityController<SigningRequestService, SigningRequest, Long> {

}