package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Palette grouping category for node types.
 * <p>
 * Used by the front-end editor to organise the node palette into sections,
 * and by {@link FlowNodeType#getCategory()} so each type knows its own group.
 */
@Getter
@AllArgsConstructor
public enum FlowNodeCategory {
    CONTROL("Control", "控制流"),
    ROUTING("Routing", "路由网关"),
    HUMAN("Human", "人工交互"),
    TASK("Task", "自动化任务"),
    SUBFLOW("Subflow", "子流程"),
    DATA("Data", "数据");

    @JsonValue
    private final String type;
    private final String name;
}
