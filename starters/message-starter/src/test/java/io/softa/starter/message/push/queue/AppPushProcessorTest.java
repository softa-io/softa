package io.softa.starter.message.push.queue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.message.push.PushPlatform;
import io.softa.starter.message.push.PushRequest;
import io.softa.starter.message.push.PushResult;
import io.softa.starter.message.push.PushResult.Outcome;
import io.softa.starter.message.push.PushSender;
import io.softa.starter.message.push.entity.AppPushDevice;
import io.softa.starter.message.push.service.AppPushDeviceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One queued message: sent once to each live device, a disowned token dropped, nothing ever tried again. */
class AppPushProcessorTest {

    private final PushSender sender = mock(PushSender.class);
    private final AppPushDeviceService devices = mock(AppPushDeviceService.class);
    private final AppPushProcessor processor = new AppPushProcessor(sender, devices);

    private static final AppPushMessage MESSAGE = new AppPushMessage(81L, 5L, 10L, "Pending approval: Leave",
            "Please review", "FlowInstance", "900", "trace-1");

    @BeforeEach
    void bothPlatformsOn() {
        when(sender.anyAvailable()).thenReturn(true);
        when(sender.available(any())).thenReturn(true);
        when(sender.send(any())).thenReturn(new PushResult(Outcome.SENT, null));
    }

    private static AppPushDevice device(String platform, String token, Long accountId) {
        AppPushDevice device = new AppPushDevice();
        device.setPlatform(platform);
        device.setDeviceToken(token);
        device.setAccountId(accountId);
        return device;
    }

    @Test
    void theMessageGoesToEachLiveDeviceOfItsRecipient_sayingTitleAndContent() {
        when(devices.liveDevices(List.of(10L))).thenReturn(List.of(device("ios", "aaa111", 10L),
                device("android", "bbb222", 10L)));

        processor.process(MESSAGE);

        ArgumentCaptor<PushRequest> sent = ArgumentCaptor.forClass(PushRequest.class);
        verify(sender, times(2)).send(sent.capture());
        PushRequest ios = sent.getAllValues().getFirst();
        assertThat(ios.platform()).isEqualTo(PushPlatform.IOS);
        assertThat(ios.deviceToken()).isEqualTo("aaa111");
        assertThat(ios.title()).isEqualTo("Pending approval: Leave");
        assertThat(ios.body()).isEqualTo("Please review");
        // Generic: the APP decides from the source model which page opens.
        assertThat(ios.data()).isEqualTo(Map.of("notificationId", "81", "tenantId", "5",
                "sourceModel", "FlowInstance", "sourceId", "900"));
        assertThat(ios.dedupeId()).isEqualTo("81");
        assertThat(ios.threadId()).isEqualTo("FlowInstance");
        assertThat(ios.timeToLive()).isEqualTo(AppPushProcessor.TIME_TO_LIVE);
        assertThat(sent.getAllValues().get(1).platform()).isEqualTo(PushPlatform.ANDROID);
    }

    @Test
    void aContentThatIsEmptyOrTheTitleAgain_isLeftOut() {
        AppPushDevice device = device("ios", "aaa111", 10L);
        assertThat(AppPushProcessor.requestFor(new AppPushMessage(1L, 5L, 10L, "Payslip ready", "Payslip ready",
                null, null, null), device, PushPlatform.IOS).body()).isNull();
        PushRequest bare = AppPushProcessor.requestFor(new AppPushMessage(1L, 5L, 10L, "Note", " ", null, null, null),
                device, PushPlatform.IOS);
        assertThat(bare.body()).isNull();
        // Nothing it refers to: only the message itself in the data.
        assertThat(bare.data()).isEqualTo(Map.of("notificationId", "1", "tenantId", "5"));
    }

    @Test
    void aTokenTheServiceDisowns_losesItsRegistration_onlyWhileStillThatAccounts() {
        when(devices.liveDevices(anyList())).thenReturn(List.of(device("ios", "gone11", 10L)));
        when(sender.send(any())).thenReturn(new PushResult(Outcome.TOKEN_INVALID, "HTTP 410 Unregistered"));

        processor.process(MESSAGE);

        verify(devices).dropInvalid("gone11", 10L);
    }

    @Test
    void everyOtherFailure_isLoggedAndLeft_theNextDeviceStillGetsIt() {
        when(devices.liveDevices(anyList())).thenReturn(List.of(device("ios", "aaa111", 10L),
                device("ios", "bbb222", 10L), device("ios", "ccc333", 10L), device("android", "ddd444", 10L)));
        when(sender.send(any())).thenReturn(new PushResult(Outcome.FAILED, "ConnectException: refused"),
                new PushResult(Outcome.REJECTED, "HTTP 400 BadDeviceToken"),
                new PushResult(Outcome.CONFIG_ERROR, "HTTP 403 InvalidProviderToken"),
                new PushResult(Outcome.SENT, null));

        processor.process(MESSAGE);

        verify(sender, times(4)).send(any());
        verify(devices, never()).dropInvalid(any(), any());
    }

    @Test
    void aPlatformWithoutCredentialsIsSkipped() {
        when(sender.available(PushPlatform.ANDROID)).thenReturn(false);
        when(devices.liveDevices(anyList())).thenReturn(List.of(device("android", "bbb222", 10L),
                device("web", "x", 10L)));

        processor.process(MESSAGE);

        verify(sender, never()).send(any());
    }

    @Test
    void withNoPlatformOn_noDeviceIsEvenLookedUp() {
        when(sender.anyAvailable()).thenReturn(false);

        processor.process(MESSAGE);

        verify(devices, never()).liveDevices(anyList());
    }

    @Test
    void anIncompleteMessageIsIgnored_andAFailureNeverReachesTheBroker() {
        processor.process(null);
        processor.process(new AppPushMessage(81L, 5L, null, "t", "c", null, null, null));
        verify(devices, never()).liveDevices(anyList());

        when(devices.liveDevices(anyList())).thenThrow(new IllegalStateException("database down"));
        assertThatCode(() -> processor.process(MESSAGE)).doesNotThrowAnyException();
    }
}
