package io.softa.starter.permission.scope;

/**
 * The shape a model uses to store where one of its rows sits in a tree: a materialized path of
 * ancestor ids, root first, in a string field named {@code idPath}.
 *
 * <p>Several places in row scoping have to agree on this, and they are far apart — the rewriter
 * that widens a filter to a subtree, the resolver that reads a row's path, the contributors that
 * compile a scope rule. Each of them once spelled {@code "idPath"} and {@code "/"} out for itself,
 * which is four copies of a decision that has to change together or not at all.
 *
 * <p>Nothing here is configurable on purpose. A model is recognised as a tree by having a field of
 * this name, so the name cannot be a setting without the recognition becoming one too.
 */
public final class IdPath {

    /** The string field holding the materialized path. Also what marks a model as a tree. */
    public static final String FIELD = "idPath";

    /**
     * What separates two ids inside a path, and therefore what has to be appended to a path before
     * it is used as a prefix match: without it {@code 1/12} is a prefix of {@code 1/120}, and a
     * subtree query would pull in unrelated branches whose ids merely start with the same digits.
     */
    public static final String SEPARATOR = "/";

    /**
     * Names the path field reachable from a model, given the dot-path from that model to the tree.
     *
     * <p>The empty path means the model is the tree itself, and is the reason this is not a plain
     * concatenation: appending the suffix to it would name {@code ".idPath"}, which looks
     * well-formed and matches nothing.
     */
    public static String fieldOn(String cascadePath) {
        return cascadePath == null || cascadePath.isEmpty() ? FIELD : cascadePath + "." + FIELD;
    }

    private IdPath() {
    }
}
