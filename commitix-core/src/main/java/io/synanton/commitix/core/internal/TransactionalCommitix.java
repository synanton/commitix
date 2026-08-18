package io.synanton.commitix.core.internal;

import io.synanton.commitix.core.Commitix;
import io.synanton.commitix.core.domain.error.NoActiveTransactionException;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.port.StorageAdapter;
import io.synanton.commitix.core.port.TransactionAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Standard {@link Commitix} implementation.
 * Validates that a transaction is active, then delegates persistence to the {@link StorageAdapter}.
 */
@Slf4j
@RequiredArgsConstructor
public final class TransactionalCommitix implements Commitix {

    private final StorageAdapter storage;
    private final TransactionAdapter transaction;

    @Override
    public void declare(Intent intent) {
        if (!transaction.isTransactionActive()) {
            throw new NoActiveTransactionException();
        }
        storage.persist(intent);
        log.debug("Declared intent id={} operation={}", intent.id(), intent.operation().id());
    }
}
