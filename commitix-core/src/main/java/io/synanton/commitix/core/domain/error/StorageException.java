package io.synanton.commitix.core.domain.error;

/** Wraps an infrastructure-level failure from a {@link io.synanton.commitix.core.port.StorageAdapter}. */
public final class StorageException extends CommitixException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
