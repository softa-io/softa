package io.softa.starter.flow.runtime;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.orm.service.EndpointAccessChecker;
import io.softa.starter.flow.runtime.engine.ActorAccounts;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ActorAccounts} over a fixed organization, for tests: the accounts that cannot act, with their status (null:
 * no account), and the users' names.
 */
public final class TestActorAccounts {

    private TestActorAccounts() {
    }

    /** Who cannot act, by account id with their status — a null status for no account — and everyone's name. */
    public static ActorAccounts of(Map<Long, String> unusable, Map<String, String> names) {
        return of(unusable, names, null);
    }

    /**
     * As {@link #of(Map, Map)}, and the account ids not allowed to approve; null for an application without an
     * {@link EndpointAccessChecker}, where everyone may.
     */
    @SuppressWarnings("unchecked")
    public static ActorAccounts of(Map<Long, String> unusable, Map<String, String> names, Set<Long> mayNotApprove) {
        OrganizationService organization = mock(OrganizationService.class);
        when(organization.getUnusableAccounts(anyCollection())).thenAnswer(invocation -> {
            Map<Long, String> found = new LinkedHashMap<>();
            for (Long accountId : (Collection<Long>) invocation.getArgument(0)) {
                if (unusable.containsKey(accountId)) {
                    found.put(accountId, unusable.get(accountId));
                }
            }
            return found;
        });
        FlowUserNames userNames = mock(FlowUserNames.class);
        when(userNames.namesOf(anyCollection())).thenAnswer(invocation -> {
            Map<String, String> found = new HashMap<>();
            for (String userId : (Collection<String>) invocation.getArgument(0)) {
                if (names.containsKey(userId)) {
                    found.put(userId, names.get(userId));
                }
            }
            return found;
        });
        if (mayNotApprove == null) {
            return new ActorAccounts(provider(organization), provider(userNames));
        }
        EndpointAccessChecker access = mock(EndpointAccessChecker.class);
        when(access.withoutAccess(anyCollection(), anyString(), anyString())).thenAnswer(invocation -> {
            Set<Long> without = new LinkedHashSet<>();
            for (Long accountId : (Collection<Long>) invocation.getArgument(0)) {
                if (mayNotApprove.contains(accountId)) {
                    without.add(accountId);
                }
            }
            return without;
        });
        return new ActorAccounts(provider(organization), provider(userNames), provider(access));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
