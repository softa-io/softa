package io.softa.starter.flow.runtime.spi.impl;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.base.annotation.TemplateVariableSet;

/**
 * What every approval mail is filled with — the eight {@code flow.*} templates
 * ({@code flow.task-assigned}, {@code flow.urged}, {@code flow.approved}, {@code flow.rejected},
 * {@code flow.returned}, {@code flow.terminated}, {@code flow.reassigned}, {@code flow.failed}) share
 * one set: they differ in wording, not in what they are told.
 */
@TemplateVariableSet(name = "flow.approval-notice", label = "Approval notice")
public record ApprovalNoticeVariables(
        @Schema(title = "Subject", description = "What the request is about", example = "Leave request LR-0012")
        String subject,
        @Schema(title = "Summary", description = "One line saying what the record asks for, as the approval "
                + "lists show it; empty for a record that words none",
                example = "Annual Leave · 12 Nov 2026 – 14 Nov 2026 · 3 days")
        String summary,
        @Schema(title = "Content", description = "The comment or reason given with the action, when there is one",
                example = "Please attach the medical certificate.")
        String content,
        @Schema(title = "Flow name", example = "Leave approval")
        String flowName,
        @Schema(title = "Record code", example = "LR-0012")
        String recordCode,
        @Schema(title = "Link", description = "Opens the request in the approval center",
                example = "https://app.example.com/approval/…")
        String link) {
}
