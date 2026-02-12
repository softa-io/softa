package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.user.entity.UserAuthFailure;
import io.softa.starter.user.service.UserAuthFailureService;

/**
 * UserAuthFailure Model Controller
 */
@Tag(name = "UserAuthFailure")
@RestController
@RequestMapping("/UserAuthFailure")
public class UserAuthFailureController extends EntityController<UserAuthFailureService, UserAuthFailure, Long> {

}