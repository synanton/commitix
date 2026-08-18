package io.synanton.commitix.core.domain.error;

import java.util.UUID;

/**
 * Thrown by the runtime when a state transition unexpectedly returns zero affected rows,
 * indicating a version/lease-generation drift that should not occur in normal operation.
 * This is a defensive safety check, not a normal CAS miss (which returns {@code false}).
 */
public final class ConcurrentUpdateException extends CommitixException {

    public ConcurrentUpdateException(UUID intentId, String operation) {
        super("Concurrent update conflict on intent " + intentId + " during " + operation);
    }
}
