package io.softa.starter.flow.runtime.spi;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@link ModelApprovalHandler} hands over when a record is submitted: the approval
 * instance's title and the routing variables its flow decides on (amounts, durations, types,
 * department, company...), and what the approval lists show of the record besides its subject.
 * Computed on the server only — nothing the client sent is passed through.
 *
 * @param title         instance title shown in inboxes and notifications
 * @param variables     the flow's start variables, null values allowed; never null itself
 * @param effectiveDate the date the record takes effect, when its model has one; null otherwise
 * @param code          the record's own number, when its model has one; null otherwise
 * @param summary       one line saying what the record asks for — the days of a leave and when, a claim's amount —
 *                      which the approval lists show beside its title so an approver can judge it there; worded by the
 *                      handler, as the title is, from {@link ApprovalSummaries}' pieces (at most 256 characters, a
 *                      longer one cut); null for none. Like the title it is shown to everyone the approval reaches —
 *                      approvers, those copied, the lists — past the fields its steps hide, so it names only what all
 *                      of them may see: never a sensitive field, such as pay
 */
public record ApprovalSubmission(String title, Map<String, Object> variables, LocalDate effectiveDate, String code,
                                 String summary) {

    /** The longest summary kept: the column's width. A longer one is cut, never refused — it is a list's line. */
    public static final int SUMMARY_MAX_LENGTH = 256;

    public ApprovalSubmission {
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        summary = summary == null || summary.isBlank() ? null
                : summary.length() > SUMMARY_MAX_LENGTH ? summary.substring(0, SUMMARY_MAX_LENGTH - 1) + "…" : summary;
    }

    /** A submission of a record with no effective date and no number of its own. */
    public ApprovalSubmission(String title, Map<String, Object> variables) {
        this(title, variables, null, null, null);
    }

    /** A submission with no summary line. */
    public ApprovalSubmission(String title, Map<String, Object> variables, LocalDate effectiveDate, String code) {
        this(title, variables, effectiveDate, code, null);
    }
}
