package io.softa.starter.flow.runtime.nodeconfig;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code SEND_SMS} nodes.
 * <p>
 * Fields hold the raw authored values ({@code {{ expr }}} placeholders included);
 * resolution is owned by the executor. Direct mode sends {@code content}; template
 * mode sends {@code templateCode} + {@code templateVariables}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendSmsConfig {

    /** Recipient phone numbers: a list of strings, or a {@code {{ expr }}} resolving to one (required). */
    private Object phoneNumbers;

    /** Direct message content; required unless {@code templateCode} is set. */
    private String content;

    /** SMS template code; takes precedence over {@code content} when set. */
    private String templateCode;

    /** Template variables; defaults to the full flow scope when omitted. */
    private Map<String, Object> templateVariables;
}
