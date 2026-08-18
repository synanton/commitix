package io.synanton.commitix.runtime.adapter.out;

import io.synanton.commitix.core.domain.error.IntentExecutionException;
import io.synanton.commitix.core.domain.model.ExecutionContext;
import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.port.ExecutionAdapter;
import io.synanton.commitix.core.port.IntentHandler;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ExecutionAdapter} that dispatches to registered {@link IntentHandler} instances.
 *
 * <p>The registry key is {@code operation.id + "@" + operation.version}.
 * Called synchronously within a virtual-thread task submitted by the {@link
 * io.synanton.commitix.runtime.domain.DispatchLoop}.
 */
@Slf4j
@RequiredArgsConstructor
public final class JvmExecutionAdapter implements ExecutionAdapter {

    private final Map<String, IntentHandler> handlers;

    @Override
    public ExecutionResult execute(Intent intent, ExecutionContext context) throws IntentExecutionException {
        String key = handlerKey(intent);
        IntentHandler handler = handlers.get(key);
        if (handler == null) {
            throw new IntentExecutionException("No handler registered for operation: " + key,
                new IllegalStateException("handler not found"));
        }
        log.debug("Executing intent id={} operation={}", intent.id(), key);
        return handler.execute(intent, context);
    }

    @Override
    public void cancel(UUID id) {
        log.debug("Cancel requested for intent id={} (best-effort, no-op)", id);
    }

    public static String handlerKey(Intent intent) {
        return intent.operation().id() + "@" + intent.operation().version();
    }
}
