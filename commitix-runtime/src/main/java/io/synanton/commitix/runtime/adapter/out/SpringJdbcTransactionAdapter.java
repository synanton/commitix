package io.synanton.commitix.runtime.adapter.out;

import io.synanton.commitix.core.domain.error.NoActiveTransactionException;
import io.synanton.commitix.core.port.TransactionAdapter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link TransactionAdapter} that integrates with Spring's transaction infrastructure.
 *
 * <p>Delegates to {@link TransactionSynchronizationManager} for detection and
 * registers an {@link TransactionSynchronization#afterCommit()} hook for callbacks.
 */
public final class SpringJdbcTransactionAdapter implements TransactionAdapter {

    @Override
    public boolean isTransactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    public void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new NoActiveTransactionException();
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
