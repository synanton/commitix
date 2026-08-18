package io.synanton.commitix.core.domain.error;

/** Thrown when {@code Commitix.declare} is called outside of an active transaction. */
public final class NoActiveTransactionException extends CommitixException {

    public NoActiveTransactionException() {
        super("Commitix.declare must be called within an active transaction");
    }
}
