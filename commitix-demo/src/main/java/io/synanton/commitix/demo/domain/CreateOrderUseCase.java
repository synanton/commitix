package io.synanton.commitix.demo.domain;

import io.synanton.commitix.core.Commitix;
import io.synanton.commitix.core.domain.model.ExecutionPolicy;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.demo.domain.model.DemoOperations;
import io.synanton.commitix.demo.domain.model.InventoryReservePayload;
import io.synanton.commitix.demo.domain.model.Order;
import io.synanton.commitix.demo.domain.model.WarehouseNotifyPayload;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demonstrates the Commitix declare-inside-transaction pattern.
 *
 * <p>The order row and both intents are persisted atomically.
 * If the transaction rolls back, neither the order nor the intents survive.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreateOrderUseCase {

    private final JdbcTemplate jdbc;
    private final Commitix commitix;

    @Transactional
    public Order createOrder(String customerId, String productId, int quantity) {
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
            "INSERT INTO demo_orders (id, customer_id, product_id, quantity, status, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
            orderId.toString(), customerId, productId, quantity,
            Order.OrderStatus.PENDING.name(), now
        );

        commitix.declare(Intent.builder()
            .id(UUID.randomUUID())
            .operation(DemoOperations.INVENTORY_RESERVE)
            .payload(new InventoryReservePayload(orderId.toString(), productId, quantity))
            .policy(ExecutionPolicy.defaultPolicy())
            .deduplicationKey(orderId + "-inventory")
            .build());

        commitix.declare(Intent.builder()
            .id(UUID.randomUUID())
            .operation(DemoOperations.WAREHOUSE_NOTIFY)
            .payload(new WarehouseNotifyPayload(orderId.toString(), productId, quantity, "WH-001"))
            .policy(ExecutionPolicy.defaultPolicy())
            .deduplicationKey(orderId + "-warehouse")
            .build());

        log.info("Order created id={} for customer={}", orderId, customerId);
        return new Order(orderId, customerId, productId, quantity, Order.OrderStatus.PENDING, now, null);
    }
}
