package io.synanton.commitix.core.port;

/**
 * SPI for discovering and participating in the ambient transaction.
 * Commitix participates in the caller's transaction; it does not own one.
 */
public interface TransactionAdapter {

    /** Returns {@code true} when a transaction is currently active on this thread. */
    boolean isTransactionActive();

    /**
     * Registers an action to run immediately after the current transaction commits successfully.
     * Has no effect if the transaction rolls back.
     */
    void afterCommit(Runnable action);
}
