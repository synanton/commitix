package io.synanton.commitix.core.port;

import io.synanton.commitix.core.domain.model.Payload;

/**
 * SPI for converting between {@link Payload} objects and raw bytes.
 * The core library does not prescribe JSON, Protobuf, Java serialization, or any other format.
 */
public interface PayloadSerializer {

    /** Serialises {@code payload} to bytes for storage. */
    byte[] serialize(Payload payload);

    /**
     * Deserialises bytes back to a {@link Payload}.
     *
     * @param bytes       the stored bytes
     * @param contentType the content type stored alongside the bytes
     */
    Payload deserialize(byte[] bytes, String contentType);
}
