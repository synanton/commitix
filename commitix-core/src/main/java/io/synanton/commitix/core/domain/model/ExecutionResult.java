package io.synanton.commitix.core.domain.model;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of a single execution attempt reported by an {@link io.synanton.commitix.core.port.IntentHandler}.
 * The runtime uses this to drive the next state transition via the StorageAdapter.
 */
public sealed interface ExecutionResult permits ExecutionResult.Success, ExecutionResult.Failure {

    /** Execution completed successfully. */
    record Success(@Nullable Payload result) implements ExecutionResult {
        public static Success empty() {
            return new Success(null);
        }
    }

    /**
     * Execution failed. The {@code action} field tells the runtime what to do next:
     * retry, permanently fail, or block for operator intervention.
     */
    record Failure(Throwable cause, FailureAction action) implements ExecutionResult {

        public Failure {
            if (cause == null) {
                throw new IllegalArgumentException("failure cause must not be null");
            }
            if (action == null) {
                throw new IllegalArgumentException("failure action must not be null");
            }
        }

        public static Failure retryable(Throwable cause) {
            return new Failure(cause, FailureAction.RETRY);
        }

        public static Failure permanent(Throwable cause) {
            return new Failure(cause, FailureAction.FAIL);
        }

        public static Failure blocking(Throwable cause) {
            return new Failure(cause, FailureAction.BLOCK);
        }
    }
}
