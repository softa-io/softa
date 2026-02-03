package ${packageName}.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import ${packageName}.entity.${modelName};
import ${packageName}.service.${modelName}Service;

/**
* ${modelName} model service implementation
* @author ${userName}
* @date ${currentDate}
*/
@Service
public class ${modelName}ServiceImpl extends EntityServiceImpl<${modelName}, Long> implements ${modelName}Service {

}