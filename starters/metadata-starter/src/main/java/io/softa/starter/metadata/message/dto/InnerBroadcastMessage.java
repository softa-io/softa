package io.softa.starter.metadata.message.dto;

import io.softa.framework.base.context.Context;
import io.softa.starter.metadata.message.enums.InnerBroadcastType;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inner broadcast message
 */
@Data
@NoArgsConstructor
public class InnerBroadcastMessage {

    private InnerBroadcastType broadcastType;

    private Context context;

}
