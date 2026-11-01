package io.softa.starter.flow.runtime;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.starter.flow.runtime.engine.ActorAccounts;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.mockito.ArgumentMatchers.anyCollection;
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
    @SuppressWarnings("unchecked")
    public static ActorAccounts of(Map<Long, String> unusable, Map<String, String> names) {
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
        return new ActorAccounts(provider(organization), provider(userNames));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
