package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * ID generation strategy.
 */
@Getter
@AllArgsConstructor
public enum IdStrategy {
    /**
     * Database Auto-increment ID
     * Type: Long (64-bit)
     */
    DB_AUTO_ID("DbAutoID", "DB Auto-increment ID"),

    /**
     * Distributed Unique ID of Long type (64-bit)
     * An implementation of SnowflakeId by CosID library.
     * Time-sorted, with 41-bit timestamp, 10-bit machine ID, and 12-bit sequence number.
     * Suitable for distributed systems to ensure uniqueness across multiple nodes.
     */
    DISTRIBUTED_LONG("DistributedLong", "Distributed Long ID"),

    /**
     * Distributed Unique ID of String type
     * An implementation of SnowflakeId by CosID library.
     * Encoded in Base36, resulting in a 13-character string.
     * Also, can be configured to be Base62, resulting in an 11-character string.
     * Suitable for distributed systems without large-scale data volume requirements.
     */
    DISTRIBUTED_STRING("DistributedString", "Distributed String ID"),

    // External ID: external input ID
    EXTERNAL_ID("ExternalID", "External ID");

    @JsonValue
    private final String type;

    private final String name;

}
