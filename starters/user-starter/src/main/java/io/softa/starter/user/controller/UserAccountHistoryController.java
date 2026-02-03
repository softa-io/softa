package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.user.entity.UserAccountHistory;
import io.softa.starter.user.service.UserAccountHistoryService;

/**
 * UserAccountHistory Model Controller
 */
@Tag(name = "UserAccountHistory")
@RestController
@RequestMapping("/UserAccountHistory")
public class UserAccountHistoryController extends EntityController<UserAccountHistoryService, UserAccountHistory, String> {

}