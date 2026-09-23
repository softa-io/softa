package io.softa.starter.permission.scope;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

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

    /**
     * A condition selecting a whole subtree: the row at {@code rootPath}, and everything under it.
     *
     * <p>Two branches rather than one prefix match. A segment carries no trailing separator, so
     * {@code LIKE '1/12%'} also matches {@code 1/120} — a different branch that merely starts with
     * the same digits. Forcing the separator excludes it, and in doing so excludes the root itself,
     * which the equality branch puts back.
     *
     * <p>Both halves are load-bearing and each fails quietly on its own: without the separator the
     * filter returns rows from unrelated branches, without the equality it returns everything under
     * the node the caller named except that node.
     *
     * @param pathField where the path is stored on the model being filtered — see {@link #fieldOn}
     * @param rootPath  the subtree root's own stored path
     */
    public static Filters subtreeOf(String pathField, String rootPath) {
        return Filters.or(
                Filters.of(pathField, Operator.EQUAL, rootPath),
                new Filters().childOf(pathField, rootPath + SEPARATOR));
    }

    /**
     * The union of several subtrees on one field: any row at or under any of the roots.
     *
     * <p>What an empty set of roots means is the caller's call — the filter rewriter matches
     * nothing, a scope contributor emits no restriction — so this insists on at least one rather
     * than deciding for them.
     */
    public static Filters subtreesOf(String pathField, Collection<String> rootPaths) {
        Iterator<String> roots = rootPaths.iterator();
        if (!roots.hasNext()) {
            throw new IllegalArgumentException("subtreesOf needs at least one root path");
        }
        Filters first = subtreeOf(pathField, roots.next());
        if (!roots.hasNext()) {
            return first;
        }
        Filters second = subtreeOf(pathField, roots.next());
        List<Filters> rest = new ArrayList<>();
        while (roots.hasNext()) {
            rest.add(subtreeOf(pathField, roots.next()));
        }
        return Filters.or(first, second, rest.toArray(new Filters[0]));
    }

    private IdPath() {
    }
}
