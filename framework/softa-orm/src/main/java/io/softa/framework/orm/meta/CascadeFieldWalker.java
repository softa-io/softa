package io.softa.framework.orm.meta;

import java.util.List;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.orm.enums.FieldType;

/**
 * Walks a dot-notation cascaded field path from a root model, dispatching callbacks
 * to a {@link Visitor} at each segment. The walker is policy-neutral: it never
 * throws on a structural failure — instead it returns a {@link Result.Failure}
 * so callers can choose between throwing, accumulating per-path errors, or
 * short-circuiting.
 *
 * <p>The same traversal shape exists inline at several points (notably
 * {@link ModelManager#getLastFieldOfCascaded} and the SQL builders); new code
 * should prefer this walker so structural rules and depth limits stay in one
 * place.
 */
public final class CascadeFieldWalker {

    /** Outcome of a single walk: either a leaf {@link MetaField} or a structural failure. */
    public sealed interface Result permits Result.Ok, Result.Failure {
        record Ok(MetaField leaf) implements Result {}
        record Failure(ErrorKind kind, int errorAt, String message) implements Result {}
    }

    /** Structural error categories surfaced by the walker. */
    public enum ErrorKind {
        /** A segment does not exist on the model being traversed. */
        FIELD_NOT_FOUND,
        /** A non-last segment is OneToMany / ManyToMany; cascading through is not allowed. */
        TRAVERSE_TO_MANY,
        /** A non-last segment is a non-relational type (e.g. String). */
        TRAVERSE_NON_RELATION,
        /** Relation hops exceed the configured maximum. */
        MAX_DEPTH_EXCEEDED
    }

    /**
     * Hooks invoked during traversal. All methods are no-op by default — implementations
     * override only what they need (alias management, access tracking, closure collection).
     */
    public interface Visitor {

        Visitor NOOP = new Visitor() { };

        /**
         * Invoked for every resolved segment, including the last.
         *
         * @param index        zero-based segment index in the path
         * @param currentModel the model the segment was resolved on
         * @param field        the resolved {@link MetaField}
         */
        default void onSegment(int index, String currentModel, MetaField field) { }

        /**
         * Invoked after a non-last ToOne segment, once the walker has decided to advance
         * into {@code nextModel}. SQL builders use this to register left joins / aliases.
         *
         * @param index     zero-based segment index that triggered the advance
         * @param field     the ToOne field being traversed
         * @param nextModel the related model the walker advances into
         */
        default void onAdvance(int index, MetaField field, String nextModel) { }
    }

    /**
     * Walk a cascaded path from {@code rootModel}, capped at {@code maxLevels} relation hops.
     *
     * <p>The boundary is inclusive: a path with exactly {@code maxLevels} hops
     * (i.e. {@code maxLevels + 1} segments) is accepted; deeper paths produce
     * {@link ErrorKind#MAX_DEPTH_EXCEEDED}.
     *
     * @param rootModel root model name; must already exist in the metadata registry
     * @param path      dot-separated path, e.g. {@code lastDeploymentId.deployStatus}
     * @param maxLevels inclusive upper bound on relation hops (e.g. {@link BaseConstant#CASCADE_LEVEL})
     * @param visitor   callback hooks; pass {@link Visitor#NOOP} to disable side effects
     * @return walk outcome
     */
    public static Result walk(String rootModel, String path, int maxLevels, Visitor visitor) {
        String[] segments = StringUtils.split(path, ".");
        if (segments == null || segments.length == 0) {
            return new Result.Failure(ErrorKind.FIELD_NOT_FOUND, 0, "Path is empty");
        }
        if (segments.length - 1 > maxLevels) {
            return new Result.Failure(ErrorKind.MAX_DEPTH_EXCEEDED, maxLevels,
                    "Path '" + path + "' exceeds the maximum cascade depth of " + maxLevels);
        }

        String currentModel = rootModel;
        MetaField field = null;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (!ModelManager.existField(currentModel, segment)) {
                return new Result.Failure(ErrorKind.FIELD_NOT_FOUND, i,
                        "Field '" + segment + "' not found on model '" + currentModel + "'");
            }
            field = ModelManager.getModelField(currentModel, segment);
            visitor.onSegment(i, currentModel, field);

            boolean isLast = (i == segments.length - 1);
            if (!isLast) {
                FieldType fieldType = field.getFieldType();
                if (FieldType.TO_MANY_TYPES.contains(fieldType)) {
                    return new Result.Failure(ErrorKind.TRAVERSE_TO_MANY, i,
                            "Cannot cascade through '" + segment + "' (" + fieldType.getType() + ")");
                }
                if (!FieldType.TO_ONE_TYPES.contains(fieldType)) {
                    return new Result.Failure(ErrorKind.TRAVERSE_NON_RELATION, i,
                            "Cannot cascade through '" + segment + "' (" + fieldType.getType() + ")");
                }
                String nextModel = field.getRelatedModel();
                visitor.onAdvance(i, field, nextModel);
                currentModel = nextModel;
            }
        }
        return new Result.Ok(field);
    }

    /** Convenience overload using {@link BaseConstant#CASCADE_LEVEL}. */
    public static Result walk(String rootModel, String path, Visitor visitor) {
        return walk(rootModel, path, BaseConstant.CASCADE_LEVEL, visitor);
    }

    /** Number of segments in a dot-notation path; useful for callers that need it post-walk. */
    public static int segmentCount(String path) {
        String[] segments = StringUtils.split(path, ".");
        return segments == null ? 0 : segments.length;
    }

    /** Split helper exposed for callers that need the raw segments. */
    public static List<String> segments(String path) {
        String[] segments = StringUtils.split(path, ".");
        return segments == null ? List.of() : List.of(segments);
    }

    private CascadeFieldWalker() { }
}
