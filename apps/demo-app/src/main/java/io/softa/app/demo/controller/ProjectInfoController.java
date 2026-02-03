package io.softa.app.demo.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.app.demo.entity.ProjectInfo;
import io.softa.app.demo.service.ProjectInfoService;

/**
 * ProjectInfo Model Controller
 */
@Tag(name = "ProjectInfo")
@RestController
@RequestMapping("/ProjectInfo")
public class ProjectInfoController extends EntityController<ProjectInfoService, ProjectInfo, Long> {

}