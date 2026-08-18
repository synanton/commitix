package io.synanton.commitix.core.testfixtures;

import io.synanton.commitix.core.domain.model.Payload;
import io.synanton.commitix.core.port.PayloadSerializer;

/**
 * Test {@link PayloadSerializer} that stores raw bytes. Supports {@link ByteArrayPayload} only.
 */
public final class ByteArrayPayloadSerializer implements PayloadSerializer {

    @Override
    public byte[] serialize(Payload payload) {
        if (payload instanceof ByteArrayPayload bytePayload) {
            return bytePayload.data();
        }
        throw new IllegalArgumentException("Unsupported payload type: " + payload.getClass());
    }

    @Override
    public Payload deserialize(byte[] bytes, String contentType) {
        return new ByteArrayPayload(contentType, bytes);
    }
}
