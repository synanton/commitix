package io.synanton.commitix.core.port;

import io.synanton.commitix.core.domain.error.IntentExecutionException;
import io.synanton.commitix.core.domain.model.ExecutionContext;
import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.Intent;
import java.util.UUID;

/**
 * SPI for executing the business operation associated with an intent.
 * Implementations dispatch to the appropriate {@link IntentHandler}.
 */
public interface ExecutionAdapter {

    /**
     * Executes the intent synchronously.
     *
     * @return the result of the execution attempt
     * @throws IntentExecutionException for infrastructure-level failures (not business failures)
     */
    ExecutionResult execute(Intent intent, ExecutionContext context) throws IntentExecutionException;

    /**
     * Best-effort request to stop currently running work for the given intent.
     * Application and adapter specific; may be a no-op.
     * Does not affect Commitix state - cancellation must be recorded through the
     * {@link StorageAdapter} independently.
     */
    void cancel(UUID id);
}
