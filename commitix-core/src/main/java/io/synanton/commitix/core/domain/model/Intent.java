package io.synanton.commitix.core.domain.model;

import java.util.UUID;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * A declaration that an operation must be performed.
 *
 * <p>Once durable (after transaction commit), an intent is immutable.
 * To change operation, payload, or policy, create a new intent with a new id.
 */
@Builder
public record Intent(
    UUID id,
    Operation operation,
    Payload payload,
    ExecutionPolicy policy,
    @Nullable String deduplicationKey
) {

    public Intent {
        if (id == null) {
            throw new IllegalArgumentException("intent id must not be null");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
    }
}
