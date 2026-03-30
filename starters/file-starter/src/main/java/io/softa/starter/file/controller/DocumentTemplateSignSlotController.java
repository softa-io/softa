package io.softa.starter.file.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.file.entity.DocumentTemplateSignSlot;
import io.softa.starter.file.service.DocumentTemplateSignSlotService;

/**
 * DocumentTemplateSignSlot Model Controller
 */
@Tag(name = "DocumentTemplateSignSlot")
@RestController
@RequestMapping("/DocumentTemplateSignSlot")
public class DocumentTemplateSignSlotController extends EntityController<DocumentTemplateSignSlotService, DocumentTemplateSignSlot, Long> {

}