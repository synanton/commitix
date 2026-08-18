package io.synanton.commitix.jdbc.adapter.out;

import io.synanton.commitix.core.domain.error.NoActiveTransactionException;
import io.synanton.commitix.core.domain.error.StorageException;
import io.synanton.commitix.core.port.TransactionAdapter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import lombok.extern.slf4j.Slf4j;

/**
 * Plain JDBC {@link TransactionAdapter} for consumers not on Spring.
 *
 * <p>The caller drives the lifecycle:
 * <ol>
 *   <li>Obtain a {@link Connection} with {@code autoCommit=false}.
 *   <li>Call {@link #begin(Connection)} to register the connection on this thread.
 *   <li>Declare intents and run business logic using the same connection.
 *   <li>Call {@link #commit()} to persist and fire after-commit callbacks,
 *       or {@link #rollback()} to discard everything.
 * </ol>
 *
 * <p>Not thread-safe; each thread manages its own transaction state.
 */
@Slf4j
public final class ManualJdbcTransactionAdapter implements TransactionAdapter {

    private static final class TxState {
        final Connection connection;
        final Deque<Runnable> afterCommitCallbacks = new ArrayDeque<>();

        TxState(Connection connection) {
            this.connection = connection;
        }
    }

    private final ThreadLocal<TxState> threadState = new ThreadLocal<>();

    /**
     * Associates a connection with the current thread's transaction.
     * The connection must already have {@code autoCommit=false}.
     *
     * @throws IllegalStateException if a transaction is already active on this thread
     */
    public void begin(Connection connection) {
        if (threadState.get() != null) {
            throw new IllegalStateException("Transaction already active on this thread");
        }
        threadState.set(new TxState(connection));
    }

    /**
     * Returns the connection registered by {@link #begin(Connection)}.
     * Wrap this in a {@code SingleConnectionDataSource} when constructing
     * a {@link JdbcStorageAdapter} to ensure the storage adapter participates in this transaction.
     *
     * @throws NoActiveTransactionException if no transaction is active
     */
    public Connection currentConnection() {
        return requireState().connection;
    }

    /**
     * Commits the underlying connection and fires registered after-commit callbacks in order.
     * Callbacks that throw are logged and skipped; the commit itself is not affected.
     *
     * @throws SQLException if the commit fails
     * @throws NoActiveTransactionException if no transaction is active
     */
    public void commit() throws SQLException {
        TxState state = requireState();
        try {
            state.connection.commit();
        } finally {
            threadState.remove();
        }
        for (Runnable callback : state.afterCommitCallbacks) {
            try {
                callback.run();
            } catch (Exception ex) {
                log.warn("After-commit callback threw an exception", ex);
            }
        }
    }

    /**
     * Rolls back the underlying connection and clears all registered callbacks.
     * Safe to call even if no transaction is active (no-op in that case).
     */
    public void rollback() {
        TxState state = threadState.get();
        if (state == null) {
            return;
        }
        threadState.remove();
        try {
            state.connection.rollback();
        } catch (SQLException ex) {
            throw new StorageException("Failed to rollback transaction", ex);
        }
    }

    @Override
    public boolean isTransactionActive() {
        return threadState.get() != null;
    }

    @Override
    public void afterCommit(Runnable action) {
        requireState().afterCommitCallbacks.add(action);
    }

    private TxState requireState() {
        TxState state = threadState.get();
        if (state == null) {
            throw new NoActiveTransactionException();
        }
        return state;
    }
}
