package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.utils.ReflectTool;

/**
 * Whether parent rows hold rows of a to-many relation, as stored now — what a required one-to-many or many-to-many
 * field is judged on once an update has written it.
 */
final class ToManyRows {

    private ToManyRows() {
    }

    /**
     * Whether every one of {@code parentIds} holds at least one row of {@code model} through {@code parentField}.
     * Counted as the write left them, in its own transaction: a soft-deleted row does not count, an inactive one
     * does — a row set aside is still there — and so does a row of any timeline slice.
     */
    static boolean allHeld(String model, String parentField, Collection<Serializable> parentIds) {
        FlexQuery query = new FlexQuery(Set.of(parentField), new Filters().in(parentField, List.copyOf(parentIds)))
                .acrossTimelineData();
        query.setFilterControl(FilterControl.bypassActiveControl());
        query.setGroupBy(parentField);
        Set<String> held = ReflectTool.searchListIgnoringRowScope(model, query).stream()
                .map(row -> String.valueOf(row.get(parentField)))
                .collect(Collectors.toSet());
        return parentIds.stream().allMatch(id -> held.contains(String.valueOf(id)));
    }
}
