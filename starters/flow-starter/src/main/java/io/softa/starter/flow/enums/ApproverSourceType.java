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
 * Where an approval step's approvers come from — its {@code approverSource}, and a CC rule's {@code recipientSource}:
 * the people named on it ({@link #USERS}), or people found when the step is reached (a supervisor, a role query, …).
 */
@Getter
@AllArgsConstructor
public enum ApproverSourceType {
    /** Specific people: the users the source names in {@link #USER_IDS}, not found. */
    USERS("Users"),
    VARIABLE_LIST("VariableList"),
    EXPRESSION("Expression"),
    INITIATOR_MANAGER("InitiatorManager"),
    ROLE("Role"),
    SUPERVISOR("Supervisor"),
    DEPT_LEADER("DeptLeader"),
    DEPT_HRBP("DeptHrbp"),
    ROLE_QUERY("RoleQuery"),
    POSITION("Position"),
    DEPARTMENT("Department"),
    ;

    /** The field a {@link #USERS} source names its people in: user ids. */
    public static final String USER_IDS = "userIds";

    @JsonValue
    private final String type;

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

    /**
     * Accepted spellings, upper-cased on both sides: the {@code @JsonValue} type and the constant
     * name. The two differ for multi-word constants — {@code VARIABLELIST} vs {@code VARIABLE_LIST}.
     */
    private static final Map<String, ApproverSourceType> NAMES_MAP = Stream.of(values()).collect(Collectors.toMap(ApproverSourceType::getType, Function.identity()));

    /**
     * Lenient parser used by the compiler to validate an untyped {@code approverSource.type}.
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

