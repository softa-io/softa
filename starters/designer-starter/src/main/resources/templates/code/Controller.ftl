package ${packageName}.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import ${packageName}.entity.${modelName};
import ${packageName}.service.${modelName}Service;

/**
* ${modelName} model controller
* @author ${userName}
* @date ${currentDate}
*/
@Tag(name = "${modelName}")
@RestController
@RequestMapping("/${modelName}")
public class ${modelName}Controller extends EntityController<${modelName}Service, ${modelName}, Long> {

}