package io.synanton.commitix.runtime.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.synanton.commitix.core.domain.error.SerializationException;
import io.synanton.commitix.core.domain.model.Payload;
import io.synanton.commitix.core.port.PayloadSerializer;
import lombok.RequiredArgsConstructor;

/**
 * {@link PayloadSerializer} backed by Jackson.
 *
 * <p>The content type must be the fully-qualified class name of the {@link Payload} implementation.
 * Concrete payload classes should return {@code getClass().getName()} from
 * {@link Payload#contentType()}.
 */
@RequiredArgsConstructor
public final class JacksonPayloadSerializer implements PayloadSerializer {

    private final ObjectMapper objectMapper;

    @Override
    public byte[] serialize(Payload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw new SerializationException("Failed to serialize payload: " + payload.contentType(), ex);
        }
    }

    @Override
    public Payload deserialize(byte[] bytes, String contentType) {
        try {
            Class<?> clazz = Class.forName(contentType);
            return (Payload) objectMapper.readValue(bytes, clazz);
        } catch (ClassNotFoundException ex) {
            throw new SerializationException("Unknown payload class: " + contentType, ex);
        } catch (Exception ex) {
            throw new SerializationException("Failed to deserialize payload of type: " + contentType, ex);
        }
    }
}
