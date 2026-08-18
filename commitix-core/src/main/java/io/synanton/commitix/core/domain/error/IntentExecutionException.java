package io.synanton.commitix.core.domain.error;

/** Wraps an infrastructure-level failure from an {@link io.synanton.commitix.core.port.ExecutionAdapter}. */
public final class IntentExecutionException extends CommitixException {

    public IntentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
