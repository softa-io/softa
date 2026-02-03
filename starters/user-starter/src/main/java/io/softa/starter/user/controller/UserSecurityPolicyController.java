package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.user.entity.UserSecurityPolicy;
import io.softa.starter.user.service.UserSecurityPolicyService;

/**
 * UserSecurityPolicy Model Controller
 */
@Tag(name = "UserSecurityPolicy")
@RestController
@RequestMapping("/UserSecurityPolicy")
public class UserSecurityPolicyController extends EntityController<UserSecurityPolicyService, UserSecurityPolicy, String> {

}