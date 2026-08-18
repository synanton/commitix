package io.synanton.commitix.core;

import io.synanton.commitix.core.domain.error.NoActiveTransactionException;
import io.synanton.commitix.core.domain.model.Intent;

/**
 * The primary Commitix API. Declares durable execution intents within the current transaction.
 *
 * <p>Usage:
 * <pre>{@code
 * @Transactional
 * public Order createOrder(CreateOrderRequest request) {
 *     Order order = orderRepository.save(Order.create(request));
 *     commitix.declare(Intent.builder()
 *         .id(UUID.randomUUID())
 *         .operation(Operation.of("InventoryReserve", "v1"))
 *         .payload(...)
 *         .policy(ExecutionPolicy.defaultPolicy())
 *         .build());
 *     return order;
 * }
 * }</pre>
 */
public interface Commitix {

    /**
     * Declares that {@code intent} must be executed. Persists within the current transaction:
     * if the transaction commits, the intent becomes durable and eligible for execution;
     * if the transaction rolls back, the intent is discarded.
     *
     * @throws NoActiveTransactionException when called outside an active transaction
     */
    void declare(Intent intent);
}
