package io.softa.framework.orm.broadcast;

/**
 * Receives an event every instance of the application must act on — typically rebuilding something it
 * holds in memory, which no shared cache can refresh for it.
 *
 * <p>Implementations are Spring beans; {@link ClusterBroadcaster} subscribes them at startup. The
 * instance that publishes an event receives it too, so a listener is the only place the work is done.
 */
public interface ClusterBroadcastListener {

    /** Name of the event this listener handles, as passed to {@link ClusterBroadcaster#publish}. */
    String event();

    /** Called once on every instance each time the event is published. */
    void onEvent();
}
