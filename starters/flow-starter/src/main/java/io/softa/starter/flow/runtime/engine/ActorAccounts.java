package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.i18n.I18n;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.service.support.FlowUserNames;

/**
 * Whether users can act in an approval. Only a user whose login account signs in can — not one not yet invited,
 * invited but not accepted, locked, frozen or gone ({@code softa.flow.org.approver-account-statuses}), as
 * {@link OrganizationService#getUnusableAccounts} tells. The approvers and CC recipients a flow resolves are
 * {@linkplain #usable kept only while they can act}, as a supervisor who left already is; a person an action names —
 * whom a task is transferred, delegated or reassigned to, someone CC'd, a delegate — is
 * {@linkplain #requireUsable refused}, naming them and why. An id that is no account id can act, and so can every
 * user when the application has no organization service.
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

    private final ObjectProvider<OrganizationService> organizationService;

    private final ObjectProvider<FlowUserNames> userNames;

    public ActorAccounts(ObjectProvider<OrganizationService> organizationService,
                         ObjectProvider<FlowUserNames> userNames) {
        this.organizationService = organizationService;
        this.userNames = userNames;
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
