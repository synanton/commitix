package io.synanton.commitix.core.testfixtures;

import io.synanton.commitix.core.domain.model.Payload;

/**
 * Minimal {@link Payload} implementation for use in tests.
 */
public record ByteArrayPayload(String contentType, byte[] data) implements Payload {

    public static ByteArrayPayload ofString(String value) {
        return new ByteArrayPayload("text/plain", value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String asString() {
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }
}
