package io.softa.starter.flow.runtime.nodeconfig;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code SEND_EMAIL} nodes.
 * <p>
 * Fields hold the raw authored values ({@code {{ expr }}} placeholders included);
 * resolution is owned by the executor. Direct mode sends subject/body; template
 * mode sends {@code templateCode} + {@code templateVariables}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendEmailConfig {

    /** Recipients: a list of strings, or a {@code {{ expr }}} resolving to one (required). */
    private Object to;

    /** CC recipients (same shapes as {@code to}). */
    private Object cc;

    /** BCC recipients (same shapes as {@code to}). */
    private Object bcc;

    private String subject;

    private String textBody;

    private String htmlBody;

    /** Mail template code; takes precedence over the direct subject/body fields when set. */
    private String templateCode;

    /** Template variables; defaults to the full flow scope when omitted. */
    private Map<String, Object> templateVariables;

    /** Mail priority: HIGH / NORMAL / LOW. */
    private String priority;
}
