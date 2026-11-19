package io.softa.starter.flow.runtime.spi.impl;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.base.annotation.TemplateVariables;

/**
 * What every approval mail is filled with — the eight {@code flow.*} templates
 * ({@code flow.task-assigned}, {@code flow.urged}, {@code flow.approved}, {@code flow.rejected},
 * {@code flow.returned}, {@code flow.terminated}, {@code flow.reassigned}, {@code flow.failed}) share
 * one set: they differ in wording, not in what they are told.
 */
@TemplateVariables(name = "flow.approval-notice", label = "Approval notice")
public record ApprovalNoticeVariables(
        @Schema(title = "Subject", description = "What the request is about", example = "Leave request LR-0012")
        String subject,
        @Schema(title = "Content", description = "The notice's own text, as the flow's settings word it",
                example = "Alex Chen approved your request.")
        String content,
        @Schema(title = "Flow name", example = "Leave approval")
        String flowName,
        @Schema(title = "Record code", example = "LR-0012")
        String recordCode,
        @Schema(title = "Link", description = "Opens the request in the approval center",
                example = "https://app.example.com/approval/…")
        String link) {
}
