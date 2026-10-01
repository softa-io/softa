package io.softa.framework.orm.broadcast;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterBroadcasterTest {

    @Test
    void aFailingListenerDoesNotStopTheOthers() {
        List<String> ran = new ArrayList<>();
        ClusterBroadcaster.dispatch(List.of(
                listener(() -> ran.add("first")),
                listener(() -> {
                    throw new IllegalStateException("rebuild failed");
                }),
                listener(() -> ran.add("third"))), "some-event");

        assertEquals(List.of("first", "third"), ran);
    }

    private static ClusterBroadcastListener listener(Runnable action) {
        return new ClusterBroadcastListener() {
            @Override
            public String event() {
                return "some-event";
            }

            @Override
            public void onEvent() {
                action.run();
            }
        };
    }
}
