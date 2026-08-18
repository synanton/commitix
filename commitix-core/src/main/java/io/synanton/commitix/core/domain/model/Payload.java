package io.synanton.commitix.core.domain.model;

/**
 * Abstract representation of operation arguments. Serialization is an adapter concern.
 * Implementations carry the content type so adapters can choose a deserializer.
 */
public interface Payload {

    /** Content type hint used by {@link io.synanton.commitix.core.port.PayloadSerializer}. */
    String contentType();
}
