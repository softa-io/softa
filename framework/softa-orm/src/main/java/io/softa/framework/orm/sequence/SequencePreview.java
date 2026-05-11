package io.softa.framework.orm.sequence;

/**
 * Read-only preview of the next sequence value.
 * Returned by {@link SequenceService#peek(String)}; the value is informational
 * and not reserved — the actual number returned by a later {@code next()} call
 * may differ if another caller allocates first.
 *
 * @param code         business code, e.g. "Employee.code"
 * @param previewValue rendered string preview, e.g. "EMP-00043"
 * @param rawNumber    raw numeric value of the preview
 * @param note         advisory string (typically "Preview only, not reserved")
 */
public record SequencePreview(
        String code,
        String previewValue,
        long rawNumber,
        String note) {
}
