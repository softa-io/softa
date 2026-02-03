package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.service.SysModelIndexService;

/**
 * SysModelIndex Model Controller
 */
@Tag(name = "SysModelIndex")
@RestController
@RequestMapping("/SysModelIndex")
public class SysModelIndexController extends EntityController<SysModelIndexService, SysModelIndex, Long> {

}