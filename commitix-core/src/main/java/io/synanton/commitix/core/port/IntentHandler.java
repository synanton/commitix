package io.synanton.commitix.core.port;

import io.synanton.commitix.core.domain.model.ExecutionContext;
import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.Intent;

/**
 * Business handler for a specific {@code operation.id + operation.version}.
 * Registered with the runtime and invoked by the {@link ExecutionAdapter}.
 *
 * <p>Handlers must be idempotent: Commitix guarantees at-least-once execution attempts.
 * Use a stable idempotency key (not the attempt number) to deduplicate side effects.
 */
public interface IntentHandler {

    /**
     * Executes the intent and returns the outcome.
     * Throw only for unexpected infrastructure failures; business failures belong in
     * {@link ExecutionResult.Failure}.
     */
    ExecutionResult execute(Intent intent, ExecutionContext context);
}
