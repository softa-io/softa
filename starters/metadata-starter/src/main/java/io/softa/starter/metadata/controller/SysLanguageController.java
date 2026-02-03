package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.entity.SysLanguage;
import io.softa.starter.metadata.service.SysLanguageService;

/**
 * SysLanguage Model Controller
 */
@Tag(name = "SysLanguage")
@RestController
@RequestMapping("/SysLanguage")
public class SysLanguageController extends EntityController<SysLanguageService, SysLanguage, String> {

}