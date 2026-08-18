package io.synanton.commitix.core.domain.error;

/**
 * Root of the Commitix exception hierarchy. All Commitix runtime exceptions extend this class.
 */
public abstract sealed class CommitixException extends RuntimeException
    permits NoActiveTransactionException,
            ConcurrentUpdateException,
            SerializationException,
            StorageException,
            IntentExecutionException {

    protected CommitixException(String message) {
        super(message);
    }

    protected CommitixException(String message, Throwable cause) {
        super(message, cause);
    }
}
