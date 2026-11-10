package io.softa.starter.message.mail.smtp;

import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import jakarta.mail.*;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.enums.SendProtocol;

/**
 * A {@link JavaMailSenderImpl} that keeps its authenticated SMTP connections
 * open between sends instead of paying a TCP connect, TLS handshake and AUTH
 * exchange for every message.
 * <p>
 * Spring's send loop is left untouched: it still calls
 * {@link #connectTransport()}, {@code sendMessage} and {@code close()}, so every
 * success and failure surfaces exactly as from a plain sender, and the error
 * codes {@link SmtpMailTransport} derives from {@code MailException}s do not
 * change. Only the connection's lifecycle differs: {@code connectTransport()}
 * lends an idle connection (or opens one), and {@code close()} hands it back.
 * <p>
 * A connection returns to the idle set only after a clean send. One that saw
 * any failure, carried {@link #MAX_MESSAGES_PER_CONNECTION} messages, or sat idle
 * past {@link #IDLE_TIMEOUT} is closed instead. An idle connection is probed with
 * {@code NOOP} before it is lent (that is what {@code SMTPTransport.isConnected()}
 * does), so a connection the server has closed costs one round trip, not a
 * failed send. The short idle timeout is what keeps that probe away from a
 * connection a NAT or load balancer dropped silently: such a connection could
 * only answer with a read timeout.
 * <p>
 * Thread-safe. A lent connection serves one send at a time, and the idle set is
 * a concurrent deque used LIFO, so the most recently used connection is lent
 * first and surplus ones age out. The set needs no size cap: every connection
 * in it was opened by a send, so it never holds more than the peak number of
 * concurrent sends.
 */
@Slf4j
class PooledSmtpSender extends JavaMailSenderImpl {

    /**
     * An idle connection older than this is closed instead of reused. Well below the idle cut-off of
     * any NAT or load balancer on the path to the SMTP server (typically minutes): a connection one of
     * those dropped silently would only answer the reuse probe with a read timeout.
     */
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * A connection is closed after carrying this many messages. Some servers cap messages per session;
     * closing first avoids a failed send, and the retry it would cost, when the cap is hit.
     */
    static final int MAX_MESSAGES_PER_CONNECTION = 100;

    private final Key key;
    private final long idleTimeoutNanos;
    private final int maxMessagesPerConnection;
    private final Deque<IdleConnection> idle = new ConcurrentLinkedDeque<>();
    private volatile boolean shutDown;

    PooledSmtpSender(Key key, Duration idleTimeout, int maxMessagesPerConnection) {
        this.key = key;
        this.idleTimeoutNanos = idleTimeout.toNanos();
        this.maxMessagesPerConnection = maxMessagesPerConnection;
    }

    /** The connection settings this pool's connections were opened with. */
    Key key() {
        return key;
    }

    @Override
    protected @NonNull Transport connectTransport() throws MessagingException {
        IdleConnection candidate;
        while ((candidate = idle.pollFirst()) != null) {
            if (!candidate.expired(System.nanoTime(), idleTimeoutNanos)
                    && candidate.connection().isConnected()) {
                return new LentTransport(getSession(), this, candidate.connection(), candidate.messagesSent());
            }
            closeQuietly(candidate.connection());
        }
        return new LentTransport(getSession(), this, openConnection(), 0);
    }

    /** Open a fresh, authenticated connection: what a plain sender does on every send. */
    Transport openConnection() throws MessagingException {
        return super.connectTransport();
    }

    /** Close the idle connections that have outlived the idle timeout. */
    void evictIdle() {
        long now = System.nanoTime();
        for (IdleConnection candidate : idle) {
            // Only the remover closes it: a concurrent send may have just taken it.
            if (candidate.expired(now, idleTimeoutNanos) && idle.removeFirstOccurrence(candidate)) {
                closeQuietly(candidate.connection());
            }
        }
    }

    /** Stop pooling: close every idle connection now, and each lent one when it comes back. */
    void shutdown() {
        shutDown = true;
        closeAllIdle();
    }

    private void giveBack(Transport connection, int messagesSent, boolean clean) {
        if (!clean || shutDown || messagesSent >= maxMessagesPerConnection) {
            closeQuietly(connection);
            return;
        }
        idle.offerFirst(new IdleConnection(connection, messagesSent, System.nanoTime()));
        if (shutDown) {
            // Raced a concurrent shutdown() that had already drained the set.
            closeAllIdle();
        }
    }

    private void closeAllIdle() {
        IdleConnection candidate;
        while ((candidate = idle.pollFirst()) != null) {
            closeQuietly(candidate.connection());
        }
    }

    private static void closeQuietly(Transport connection) {
        try {
            connection.close();
        } catch (MessagingException | RuntimeException e) {
            log.debug("PooledSmtpSender: closing a pooled SMTP connection failed: {}", e.getMessage());
        }
    }

    /**
     * Everything a pooled connection was opened with. A config whose key changes
     * (host moved, password rotated, TLS toggled) gets a new pool on its next
     * send, so a connection authenticated with the old settings is never lent
     * again.
     */
    record Key(SendProtocol protocol, String host, Integer port, String username, String password,
               boolean sslEnabled, boolean starttlsEnabled) {

        static Key of(MailSendServerConfig config) {
            return new Key(config.getProtocol(), config.getHost(), config.getPort(),
                    config.getUsername(), config.getPassword(),
                    Boolean.TRUE.equals(config.getSslEnabled()),
                    Boolean.TRUE.equals(config.getStarttlsEnabled()));
        }

        /** Never renders the password. */
        @Override
        public @NonNull String toString() {
            return protocol + "://" + username + "@" + host + ":" + port;
        }
    }

    private record IdleConnection(Transport connection, int messagesSent, long idleSinceNanos) {

        boolean expired(long nowNanos, long timeoutNanos) {
            return nowNanos - idleSinceNanos >= timeoutNanos;
        }
    }

    /**
     * The connection as Spring's send loop sees it: messages go to the pooled
     * connection, and {@code close()} hands it back to the pool instead of
     * hanging up.
     */
    private static final class LentTransport extends Transport {

        private final PooledSmtpSender pool;
        private final Transport connection;
        private int messagesSent;
        private boolean clean = true;
        private boolean returned;

        LentTransport(Session session, PooledSmtpSender pool, Transport connection, int messagesSent) {
            super(session, connection.getURLName());
            this.pool = pool;
            this.connection = connection;
            this.messagesSent = messagesSent;
        }

        @Override
        public void sendMessage(Message message, Address[] addresses) throws MessagingException {
            try {
                connection.sendMessage(message, addresses);
                messagesSent++;
            } catch (MessagingException | RuntimeException e) {
                clean = false;
                throw e;
            }
        }

        @Override
        public boolean isConnected() {
            return !returned && connection.isConnected();
        }

        @Override
        public void close() {
            if (!returned) {
                returned = true;
                pool.giveBack(connection, messagesSent, clean);
            }
        }
    }
}
