package io.synanton.commitix.demo.domain.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Demo aggregate: a placed order.
 * Stored in the {@code demo_orders} table.
 */
public record Order(
    UUID id,
    String customerId,
    String productId,
    int quantity,
    OrderStatus status,
    Instant createdAt,
    @Nullable String failureReason
) {

    public enum OrderStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}
