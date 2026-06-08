package io.softa.framework.orm.meta;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AppStartup}.
 *
 * <p>Covers:
 * <ul>
 *   <li>boot path with no pre-initializers (backward-compatible behavior)</li>
 *   <li>boot path with pre-initializers (called before managers)</li>
 *   <li>reload path skips pre-initializers (avoids re-applying DDL etc.)</li>
 *   <li>repeated boot + reload invocations behave correctly</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AppStartupTest {

    @Mock private ModelManager modelManager;
    @Mock private OptionManager optionManager;
    @Mock private TranslationCache translationCache;

    @InjectMocks private AppStartup appStartup;

    @Test
    void afterPropertiesSet_withoutInitializers_initsAllManagers() {
        // preInitializers field defaults to List.of() via field initializer
        appStartup.afterPropertiesSet();

        verify(modelManager).init();
        verify(optionManager).init();
        verify(translationCache).init();
    }

    @Test
    void afterPropertiesSet_withInitializers_callsBeforeManagers() {
        List<String> callOrder = new ArrayList<>();
        MetadataInitializer first = new RecordingInitializer("first", callOrder);
        MetadataInitializer second = new RecordingInitializer("second", callOrder);

        Mockito.doAnswer(inv -> {
            callOrder.add("modelManager.init");
            return null;
        }).when(modelManager).init();
        Mockito.doAnswer(inv -> {
            callOrder.add("optionManager.init");
            return null;
        }).when(optionManager).init();
        Mockito.doAnswer(inv -> {
            callOrder.add("translationCache.init");
            return null;
        }).when(translationCache).init();

        ReflectionTestUtils.setField(appStartup, "preInitializers", List.of(first, second));

        appStartup.afterPropertiesSet();

        // All pre-initializers run before managers; managers run in fixed
        // order: model -> option -> translation.
        assertThat(callOrder).containsExactly(
                "first", "second",
                "modelManager.init", "optionManager.init", "translationCache.init");
    }

    @Test
    void reloadMetadata_skipsPreInitializers() {
        MetadataInitializer init = Mockito.mock(MetadataInitializer.class);
        ReflectionTestUtils.setField(appStartup, "preInitializers", List.of(init));

        appStartup.reloadMetadata();

        verifyNoInteractions(init);
        verify(modelManager).init();
        verify(optionManager).init();
        verify(translationCache).init();
    }

    @Test
    void bootThenReload_initializerCalledOnceManagersCalledMultipleTimes() {
        MetadataInitializer init = Mockito.mock(MetadataInitializer.class);
        ReflectionTestUtils.setField(appStartup, "preInitializers", List.of(init));

        appStartup.afterPropertiesSet();
        appStartup.reloadMetadata();
        appStartup.reloadMetadata();

        verify(init, times(1)).initialize();
        verify(modelManager, times(3)).init();
        verify(optionManager, times(3)).init();
        verify(translationCache, times(3)).init();
    }

    private static class RecordingInitializer implements MetadataInitializer {
        private final String name;
        private final List<String> callOrder;

        RecordingInitializer(String name, List<String> callOrder) {
            this.name = name;
            this.callOrder = callOrder;
        }

        @Override
        public void initialize() {
            callOrder.add(name);
        }
    }
}
