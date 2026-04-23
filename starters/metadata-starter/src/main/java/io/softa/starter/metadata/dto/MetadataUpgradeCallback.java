package io.softa.starter.metadata.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Runtime → studio completion payload. POSTed to the {@code callbackUrl} carried in the
 * originating {@link MetadataUpgradeRequest}, with the request's {@code callbackToken}
 * echoed in the {@code X-Softa-Callback-Token} header so the studio can match the
 * callback to a pending deployment.
 * <p>
 * The token alone is sufficient authentication for the callback because (a) it was
 * generated server-side on the studio, (b) it was only ever transmitted over the
 * signed outbound request, and (c) each deployment's token is rotated and burned on
 * first receipt.
 */
@Data
@NoArgsConstructor
public class MetadataUpgradeCallback implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** {@code SUCCESS} or {@code FAILURE}. */
    private String status;

    /** First-line failure reason when {@code status == FAILURE}; null otherwise. */
    private String errorMessage;

    /** Upgrade wall-clock duration measured on the runtime side, in milliseconds. */
    private Long durationMillis;
}
