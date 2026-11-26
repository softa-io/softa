package io.softa.starter.flow.enums;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Where a group of people comes from — each of an approval step's {@code approverSources}, and a CC rule's
 * {@code recipientSource}: the people named on it ({@link #USERS}), or people found when the step is reached (a
 * supervisor, a role query, …).
 */
@Getter
@AllArgsConstructor
public enum ApproverSourceType {
    /** Specific people: the users the source names in {@link #USER_IDS}, not found. */
    USERS("Users", false),
    /** One manager: the Nth up the chain. */
    SUPERVISOR("Supervisor", true),
    /** The leader of one department. */
    DEPT_LEADER("DeptLeader", true),
    /** The HR business partner of one department, as {@link #DEPT_LEADER}. */
    DEPT_HRBP("DeptHrbp", true),
    /**
     * The person a field of the record names — an employee or a login account, such as a transfer's new manager: the
     * field {@link #FIELD} says, one of the record's own.
     */
    RECORD_FIELD("RecordField", true),
    ROLE_QUERY("RoleQuery", false),
    POSITION("Position", false),
    DEPARTMENT("Department", false),
    ;

    /** The field a {@link #USERS} source names its people in: user ids. */
    public static final String USER_IDS = "userIds";

    /** The field of the record a {@link #RECORD_FIELD} source reads its person from: the field's name. */
    public static final String FIELD = "field";

    @JsonValue
    private final String type;

    /**
     * Whether a group of this source finds one person at most: a step with only such a group has one approver at most,
     * and its vote threshold does not apply.
     */
    private final boolean findsOne;

    /** The source a group's {@code type} names; null for none, or one this version does not know. */
    public static ApproverSourceType lookup(Object type) {
        return type == null ? null : NAMES_MAP.get(type.toString());
    }

    /**
     * The users a source names — a {@link #USERS} source's {@link #USER_IDS}, blank ones left out — or none: no source,
     * or one that finds its people when the step is reached.
     */
    public static List<String> namedUsers(Map<String, Object> source) {
        if (source == null || !USERS.type.equals(source.get("type")) || !(source.get(USER_IDS) instanceof Collection<?> ids)) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).map(Object::toString).map(String::trim)
                .filter(id -> !id.isEmpty()).toList();
    }

    /** The sources by their {@code type}, the {@code @JsonValue}. */
    private static final Map<String, ApproverSourceType> NAMES_MAP = Stream.of(values()).collect(Collectors.toMap(ApproverSourceType::getType, Function.identity()));

    /**
     * Lenient parser used by the compiler to validate an untyped group's {@code type}.
     * Not a Jackson hook — typed JSON binding goes through {@link JsonValue}.
     *
     * <p>Throws the JDK {@code IllegalArgumentException} deliberately:
     * {@code ApprovalConfigValidator} catches that type to turn an unknown source into a compile
     * diagnostic. Do not route this through {@code Assert} — softa's {@code IllegalArgumentException}
     * extends {@code BaseException}, not the JDK one, so that catch would silently stop matching.
     */
    public static ApproverSourceType of(String value) {
        ApproverSourceType sourceType = value == null ? null : NAMES_MAP.get(value);
        if (sourceType == null) {
            throw new IllegalArgumentException("Unsupported approver source type: " + value);
        }
        return sourceType;
    }
}

