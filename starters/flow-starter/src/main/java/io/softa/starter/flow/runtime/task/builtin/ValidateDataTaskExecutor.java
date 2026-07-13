package io.softa.starter.flow.runtime.task.builtin;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.ValidateDataConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

/**
 * Built-in DataTask executor for validating data with an expression.
 * <p>
 * Evaluates a boolean expression against the current execution variables.
 * When the expression evaluates to {@code false}, a {@link BusinessException}
 * is thrown with the configured exception message (which supports string
 * interpolation via {@code {{ var }}}).
 * </p>
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "ValidateData",
 *   "input": {
 *     "expression": "amount > 0 && amount <= budget",
 *     "exceptionMsg": "Amount {{ amount }} exceeds budget {{ budget }}!"
 *   }
 * }
 * }</pre>
 */
@Component
public class ValidateDataTaskExecutor extends AbstractTaskExecutor {

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.VALIDATE_DATA;
    }

    @Override
    public String getExecutor() {
        return "ValidateData";
    }

    @Override
    public String getName() {
        return "Validate Data";
    }

    @Override
    public String getDescription() {
        return "Validate data with a boolean expression. Throws BusinessException when validation fails.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        return Map.of(
                "expression", Map.of("type", "expression", "label", "Expression", "required", true),
                "exceptionMsg", Map.of("type", "string", "label", "Exception Message", "required", true)
        );
    }

    @Override
    public String getIcon() {
        return "check-square";
    }

    @Override
    public int getSortOrder() {
        return 54;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        ValidateDataConfig cfg = requireConfig(request, ValidateDataConfig.class);
        String expression = requireText(cfg.getExpression(), "expression");
        String exceptionMsg = requireText(cfg.getExceptionMsg(), "exceptionMsg");

        boolean passed = ComputeUtils.executeBoolean(expression, new LinkedHashMap<>(variables));
        if (!passed) {
            String message = ComputeUtils.stringInterpolation(exceptionMsg, new LinkedHashMap<>(variables));
            throw new BusinessException(message);
        }
        return Map.of("valid", true);
    }
}

