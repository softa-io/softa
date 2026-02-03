package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.service.SysFieldService;

/**
 * SysField Model Controller
 */
@Tag(name = "SysField")
@RestController
@RequestMapping("/SysField")
public class SysFieldController extends EntityController<SysFieldService, SysField, Long> {

}