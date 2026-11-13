package io.softa.starter.sentry;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import io.sentry.EventProcessor;
import io.sentry.Hint;
import io.sentry.SentryBaseEvent;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryTransaction;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.orm.constant.RPCConstant;
import io.softa.framework.web.signature.SignatureConstant;
import io.softa.framework.web.utils.SecretMasker;

/**
 * Masks credentials in the request Sentry attaches to every event and transaction. The SDK copies the scope's
 * request onto each of them, with the query string and the headers as they arrived, and with
 * {@code send-default-pii: false} drops only the standard credential headers it knows (Authorization, Cookie,
 * X-Api-Key, ...). What it cannot know is masked here, before anything is sent:
 * <ul>
 *   <li>the query string, by the names softa-web masks in log lines ({@link SecretMasker}) — the
 *       {@code token} of a signing link, say;</li>
 *   <li>softa's own credential headers: the APP session ({@code X-Session-Id}), the RPC credentials and the
 *       upgrade callback token;</li>
 *   <li>the request body, attached only when an application turns on {@code send-default-pii} and
 *       {@code max-request-body-size}, the way a log line masks it.</li>
 * </ul>
 * The request is masked on a copy, since the scope's own is shared by every event of the request.
 */
@Component
public class SentryRequestMasker implements EventProcessor {

    /** softa's credential headers, lower-cased. */
    private static final Set<String> SECRET_HEADERS = Stream.of(BaseConstant.SESSION_ID_HEADER,
                    RPCConstant.RPC_HEADER_API_KEY, RPCConstant.RPC_HEADER_API_SECRET, SignatureConstant.CALLBACK_TOKEN)
            .map(header -> header.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public SentryEvent process(@NonNull SentryEvent event, @NonNull Hint hint) {
        mask(event);
        return event;
    }

    @Override
    public SentryTransaction process(@NonNull SentryTransaction transaction, @NonNull Hint hint) {
        mask(transaction);
        return transaction;
    }

    private static void mask(SentryBaseEvent event) {
        if (event.getRequest() == null) {
            return;
        }
        Request request = new Request(event.getRequest());
        request.setQueryString(SecretMasker.maskQueryString(request.getQueryString()));
        if (request.getHeaders() != null) {
            request.getHeaders().replaceAll((name, value) ->
                    SECRET_HEADERS.contains(name.toLowerCase(Locale.ROOT)) ? SecretMasker.MASK : value);
        }
        if (request.getData() instanceof String body) {
            request.setData(SecretMasker.maskJson(body));
        }
        event.setRequest(request);
    }
}
