package io.synanton.commitix.core.domain.error;

/** Thrown when a {@link io.synanton.commitix.core.port.PayloadSerializer} fails. */
public final class SerializationException extends CommitixException {

    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
