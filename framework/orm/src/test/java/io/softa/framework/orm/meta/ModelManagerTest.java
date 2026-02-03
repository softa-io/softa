package io.softa.framework.orm.meta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.compute.ComputeUtils;

@Slf4j
class ModelManagerTest {

    @Test
    void initComputedFields() {
        String formula = "if seq != \"6\" { \"17\" } else { \"99\" }";
        List<String> dependentFields = ComputeUtils.getVariables(formula);
        Assertions.assertNotNull(dependentFields);
        Map<String, Object> env = new HashMap<>();
        env.put("seq", "5");
        Object result = ComputeUtils.execute(formula, env);
        log.info(result.toString());
    }
}