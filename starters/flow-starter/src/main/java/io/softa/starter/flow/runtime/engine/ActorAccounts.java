package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.i18n.I18n;
import io.softa.framework.orm.service.EndpointAccessChecker;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.service.support.FlowUserNames;

/**
 * Whether users can act in an approval. Only a user whose login account signs in can — not one not yet invited,
 * invited but not accepted, locked, frozen or gone — as {@link OrganizationService#getUnusableAccounts} tells. The
 * approvers and CC recipients a flow resolves are {@linkplain #usable kept only while they can act}, as a supervisor
 * who left already is; a person an action names — whom a task is transferred, delegated or reassigned to, someone
 * CC'd, a delegate — is {@linkplain #requireUsable refused}, naming them and why. An id that is no account id can act, and so can every
 * user when the application has no organization service.
 *
 * <p>Someone handed a task to decide — whom it is transferred, reassigned or delegated to — must also be allowed to
 * approve: {@linkplain #requireCanApprove refused} otherwise, as the permission gate would refuse them the approval
 * and leave the task stuck with them. A CC'd person only reads, and is not asked this. Every user may approve when
 * the application has no {@link EndpointAccessChecker}.
 */
@Component
public class ActorAccounts {

    /** Why a user without an account cannot act; {0} is their name. */
    private static final String NO_ACCOUNT = "{0} has no account";

    /** Why a user cannot act, by their account status; {0} is their name. */
    private static final Map<String, String> REASONS = Map.of(
            "Pending", "{0}''s account has not been activated yet",
            "Invited", "{0} has not accepted the invitation to their account yet",
            "Locked", "{0}''s account is locked",
            "Frozen", "{0}''s account is frozen",
            "Deactivated", "{0}''s account is deactivated");

    /** Why a user cannot act, for another status the application does not count as signing in; {1} is it. */
    private static final String OTHER_STATUS = "{0}''s account is {1}";

    /** Why a user who signs in still cannot decide a task; {0} is their name. */
    private static final String CANNOT_APPROVE = "{0} is not allowed to approve";

    /** The endpoint a task is decided through: whoever cannot call it cannot decide one. */
    static final String APPROVE_METHOD = "POST";
    static final String APPROVE_PATH = "/flow/instances/approve";

    private final ObjectProvider<OrganizationService> organizationService;

    private final ObjectProvider<FlowUserNames> userNames;

    private final ObjectProvider<EndpointAccessChecker> endpointAccess;

    public ActorAccounts(ObjectProvider<OrganizationService> organizationService,
                         ObjectProvider<FlowUserNames> userNames) {
        this(organizationService, userNames, null);
    }

    @Autowired
    public ActorAccounts(ObjectProvider<OrganizationService> organizationService,
                         ObjectProvider<FlowUserNames> userNames,
                         ObjectProvider<EndpointAccessChecker> endpointAccess) {
        this.organizationService = organizationService;
        this.userNames = userNames;
        this.endpointAccess = endpointAccess;
    }

    /** {@code userIds} without the users who cannot act, in their order. */
    public List<String> usable(List<String> userIds) {
        Map<String, String> unusable = statuses(userIds);
        return unusable.isEmpty() ? userIds : userIds.stream().filter(id -> !unusable.containsKey(id)).toList();
    }

    /**
     * Why each of {@code userIds} who cannot act cannot, by user id in their order, naming them: "Ann Lee's account is
     * frozen". Empty when they all can.
     */
    public Map<String, String> whyUnusable(Collection<String> userIds) {
        Map<String, String> unusable = statuses(userIds);
        if (unusable.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = namesOf(unusable.keySet());
        Map<String, String> why = new LinkedHashMap<>();
        unusable.forEach((userId, status) -> why.put(userId, reason(names.get(userId), status)));
        return why;
    }

    /** Refuses ({@code 400}) when any of {@code userIds} cannot act, naming each of them and why. */
    public void requireUsable(Collection<String> userIds) {
        Map<String, String> why = whyUnusable(userIds);
        if (!why.isEmpty()) {
            throw new FlowActionValidationException(String.join("; ", why.values()) + ".");
        }
    }

    /**
     * Why each of {@code userIds} who cannot decide a task cannot, by user id in their order, naming them: their
     * account, as {@link #whyUnusable}, or that they are not allowed to approve. Empty when they all can.
     */
    public Map<String, String> whyCannotApprove(Collection<String> userIds) {
        Map<String, String> why = new LinkedHashMap<>(whyUnusable(userIds));
        Set<String> withoutApproval = withoutApproval(userIds, why.keySet());
        if (withoutApproval.isEmpty()) {
            return why;
        }
        Map<String, String> names = namesOf(withoutApproval);
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String userId : userIds) {
            if (why.containsKey(userId)) {
                ordered.put(userId, why.get(userId));
            } else if (withoutApproval.contains(userId)) {
                ordered.put(userId, I18n.get(CANNOT_APPROVE, names.get(userId)));
            }
        }
        return ordered;
    }

    /** Refuses ({@code 400}) when any of {@code userIds} cannot decide a task, naming each of them and why. */
    public void requireCanApprove(Collection<String> userIds) {
        Map<String, String> why = whyCannotApprove(userIds);
        if (!why.isEmpty()) {
            throw new FlowActionValidationException(String.join("; ", why.values()) + ".");
        }
    }

    /** {@code userIds} without those who cannot decide a task, in their order. */
    public List<String> ableToApprove(List<String> userIds) {
        Map<String, String> why = whyCannotApprove(userIds);
        return why.isEmpty() ? userIds : userIds.stream().filter(id -> !why.containsKey(id)).toList();
    }

    /** Of {@code userIds} not already {@code ruledOut}, the account ids not allowed to approve. */
    private Set<String> withoutApproval(Collection<String> userIds, Set<String> ruledOut) {
        EndpointAccessChecker checker = endpointAccess == null ? null : endpointAccess.getIfAvailable();
        if (checker == null || userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, String> byAccount = new LinkedHashMap<>();
        for (String userId : userIds) {
            Long accountId = ruledOut.contains(userId) ? null : toLong(userId);
            if (accountId != null) {
                byAccount.put(accountId, userId);
            }
        }
        if (byAccount.isEmpty()) {
            return Set.of();
        }
        // Their own permissions, not the caller's: whoever asks, the answer is about the people named.
        Set<Long> without = ContextUtils.withoutPermissionChecks(
                () -> checker.withoutAccess(byAccount.keySet(), APPROVE_METHOD, APPROVE_PATH));
        if (without == null || without.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        byAccount.forEach((accountId, userId) -> {
            if (without.contains(accountId)) {
                ids.add(userId);
            }
        });
        return ids;
    }

    /** The users' names by user id, as the approval views give them; a user without a name is named by their id. */
    public Map<String, String> namesOf(Collection<String> userIds) {
        List<String> ids = userIds.stream().filter(StringUtils::hasText).distinct().toList();
        FlowUserNames names = userNames == null ? null : userNames.getIfAvailable();
        Map<String, String> found = names == null || ids.isEmpty() ? Map.of() : names.namesOf(ids);
        Map<String, String> named = new HashMap<>();
        ids.forEach(id -> named.put(id, found.getOrDefault(id, id)));
        return named;
    }

    /** The status of each of {@code userIds} who cannot act, null for one without an account, in their order. */
    private Map<String, String> statuses(Collection<String> userIds) {
        OrganizationService organization = organizationService == null ? null : organizationService.getIfAvailable();
        if (organization == null || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> byAccount = new LinkedHashMap<>();
        for (String userId : userIds) {
            Long accountId = toLong(userId);
            if (accountId != null) {
                byAccount.put(accountId, userId);
            }
        }
        if (byAccount.isEmpty()) {
            return Map.of();
        }
        // Who can act is the organization's answer, not the caller's: their permissions do not narrow it.
        Map<Long, String> unusable = ContextUtils.withoutPermissionChecks(
                () -> organization.getUnusableAccounts(List.copyOf(byAccount.keySet())));
        if (unusable == null || unusable.isEmpty()) {
            return Map.of();
        }
        Map<String, String> statuses = new LinkedHashMap<>();
        byAccount.forEach((accountId, userId) -> {
            if (unusable.containsKey(accountId)) {
                statuses.put(userId, unusable.get(accountId));
            }
        });
        return statuses;
    }

    private static String reason(String name, String status) {
        if (status == null) {
            return I18n.get(NO_ACCOUNT, name);
        }
        String reason = REASONS.get(status);
        return reason != null ? I18n.get(reason, name) : I18n.get(OTHER_STATUS, name, status.toLowerCase(Locale.ROOT));
    }

    private static Long toLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
