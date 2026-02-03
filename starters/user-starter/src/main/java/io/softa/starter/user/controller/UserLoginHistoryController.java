package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.user.entity.UserLoginHistory;
import io.softa.starter.user.service.UserLoginHistoryService;

/**
 * UserLoginHistory Model Controller
 */
@Tag(name = "UserLoginHistory")
@RestController
@RequestMapping("/UserLoginHistory")
public class UserLoginHistoryController extends EntityController<UserLoginHistoryService, UserLoginHistory, String> {

}