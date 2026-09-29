package io.softa.starter.metadata.seed;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.metadata.enums.SeedSyncTriggerType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A company saved in a country brings its tenant up to date once its transaction commits — once per
 * transaction, and not at all when it rolls back or the country did not change.
 */
class CompanyCountrySeedTriggerTest {

    private static final Long TENANT = 7L;
    private static final String COUNTRY = ModelConstant.COUNTRY_FIELD;

    private SeedSyncService seedSyncService;
    private CompanyCountrySeedTrigger trigger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        seedSyncService = mock(SeedSyncService.class);
        ObjectProvider<SeedSyncService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(seedSyncService);
        trigger = new CompanyCountrySeedTrigger(provider);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(CompanyCountrySeedTrigger.class.getName() + ".tenants");
    }

    @Test
    void onlyCompaniesAreWatched() {
        assertThat(trigger.supports(ModelConstant.COMPANY_MODEL)).isTrue();
        assertThat(trigger.supports("Department")).isFalse();
    }

    @Test
    void companiesCreatedInACountryReconcileTheirTenantOnceTheWriteCommits() {
        inTenant(() -> {
            trigger.validateCreate(create(Map.of(COUNTRY, "SG")));
            trigger.validateCreate(create(Map.of(COUNTRY, "NZ")));
        });
        verify(seedSyncService, after(300).never()).reconcile(any(), any());

        complete(TransactionSynchronization.STATUS_COMMITTED);

        verify(seedSyncService, timeout(2000).times(1)).reconcile(List.of(TENANT), SeedSyncTriggerType.COUNTRY_ADDED);
    }

    @Test
    void aWriteThatRollsBackTriggersNothing() {
        inTenant(() -> trigger.validateCreate(create(Map.of(COUNTRY, "SG"))));

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(seedSyncService, after(300).never()).reconcile(any(), any());
    }

    @Test
    void aCompanyWithoutACountryTriggersNothing() {
        inTenant(() -> trigger.validateCreate(create(Map.of("name", "Acme"))));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void anUpdateReconcilesOnlyWhenTheCountryChanges() {
        inTenant(() -> trigger.validateUpdate(update(Map.of(COUNTRY, "SG"), Map.of(COUNTRY, "SG"))));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();

        inTenant(() -> trigger.validateUpdate(update(Map.of(COUNTRY, "NZ"), Map.of(COUNTRY, "SG"))));
        complete(TransactionSynchronization.STATUS_COMMITTED);

        verify(seedSyncService, timeout(2000)).reconcile(List.of(TENANT), SeedSyncTriggerType.COUNTRY_ADDED);
    }

    private static WriteContext create(Map<String, Object> row) {
        return WriteContext.of(ModelConstant.COMPANY_MODEL, AccessType.CREATE, row, null);
    }

    private static WriteContext update(Map<String, Object> row, Map<String, Object> original) {
        return WriteContext.of(ModelConstant.COMPANY_MODEL, AccessType.UPDATE, row, original);
    }

    private static void inTenant(Runnable body) {
        Context context = new Context();
        context.setTenantId(TENANT);
        ContextHolder.runWith(context, body);
    }

    private static void complete(int status) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(s -> s.afterCompletion(status));
    }
}
