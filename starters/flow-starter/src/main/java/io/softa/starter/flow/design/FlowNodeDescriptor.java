package io.softa.starter.flow.design;

import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.flow.enums.FlowNodeCategory;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Describes a draggable node type for both frontend rendering and backend validation.
 * <p>
 * One {@link FlowNodeType} may map 1:1 to a descriptor (structural nodes) or a
 * {@link io.softa.starter.flow.runtime.task.TaskExecutor} may produce its own descriptor
 * based on its {@code getSupportedNodeType()} discriminator. Build instances through
 * {@link #of} so {@code category} always follows the node type.
 */
@Schema(name = "FlowNodeDescriptor")
public record FlowNodeDescriptor(

        @Schema(description = "Unique type key used as the xyflow node type and backend node type")
        FlowNodeType type,

        @Schema(description = "Palette section this node type belongs to")
        FlowNodeCategory category,

        @Schema(description = "Display label")
        String label,

        @Schema(description = "Description of the node's purpose")
        String description,

        @Schema(description = "Icon identifier for the node palette")
        String icon,

        @Schema(description = "Sort order in the palette (lower = higher)")
        int sortOrder,

        @Schema(description = "Connection constraints for react-flow isValidConnection; null = unconstrained")
        Ports ports,

        @Schema(description = "JSON Schema-like descriptor of config fields")
        Map<String, Object> configSchema,

        @Schema(description = "Default config values when dropped onto the canvas")
        Map<String, Object> defaultConfig,

        @Schema(description = "Flow scenarios that allow this node type")
        Set<FlowScenario> allowedScenarios,

        @Schema(description = "False = preview/stub node type, hidden from the default palette listing")
        boolean productionReady
) {

    /** Edge-count constraints; a null bound means unbounded. */
    @Schema(name = "FlowNodeDescriptorPorts")
    public record Ports(

            @Schema(description = "Maximum incoming edges; null = unbounded")
            Integer maxIncoming,

            @Schema(description = "Maximum outgoing edges; null = unbounded")
            Integer maxOutgoing
    ) {
        public static Ports entry() {
            return new Ports(0, null);
        }

        public static Ports terminal() {
            return new Ports(null, 0);
        }
    }

    /** Canonical factory — derives {@code category} from the node type. */
    public static FlowNodeDescriptor of(FlowNodeType type, String label, String description,
                                        String icon, int sortOrder, Ports ports,
                                        Map<String, Object> configSchema,
                                        Map<String, Object> defaultConfig,
                                        Set<FlowScenario> allowedScenarios,
                                        boolean productionReady) {
        return new FlowNodeDescriptor(type, type.getCategory(), label, description, icon, sortOrder,
                ports, configSchema, defaultConfig, allowedScenarios, productionReady);
    }

    /** Copy with a different label/description (used for i18n at the REST boundary). */
    public FlowNodeDescriptor withText(String newLabel, String newDescription) {
        return new FlowNodeDescriptor(type, category, newLabel, newDescription, icon, sortOrder,
                ports, configSchema, defaultConfig, allowedScenarios, productionReady);
    }
}
