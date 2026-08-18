package io.synanton.commitix.demo.domain.handler;

import io.synanton.commitix.core.domain.model.ExecutionContext;
import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.port.IntentHandler;
import io.synanton.commitix.demo.adapter.out.InventoryClient;
import io.synanton.commitix.demo.domain.model.DemoOperations;
import io.synanton.commitix.demo.domain.model.InventoryReservePayload;
import io.synanton.commitix.runtime.config.CommitixAutoConfiguration.OperationKeyProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Handles {@code INVENTORY_RESERVE@v1} intents.
 *
 * <p>Idempotency is implemented via an {@code idempotency_keys} table.
 * If the key already exists the reservation was already made; we return success immediately.
 * This demonstrates §5.6 (idempotency is the application's responsibility).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReserveHandler implements IntentHandler, OperationKeyProvider {

    private final JdbcTemplate jdbc;
    private final InventoryClient inventoryClient;

    @Override
    public String operationKey() {
        return DemoOperations.INVENTORY_RESERVE_KEY;
    }

    @Override
    public ExecutionResult execute(Intent intent, ExecutionContext context) {
        InventoryReservePayload payload = (InventoryReservePayload) intent.payload();
        String idempotencyKey = payload.orderId() + "-reservation";

        if (isAlreadyProcessed(idempotencyKey)) {
            log.info("Inventory reservation already processed for orderId={}", payload.orderId());
            return new ExecutionResult.Success(null);
        }

        inventoryClient.reserveStock(payload.orderId(), payload.productId(), payload.quantity());

        markProcessed(idempotencyKey, intent.id().toString());
        log.info("Inventory reserved for orderId={}", payload.orderId());
        return new ExecutionResult.Success(null);
    }

    private boolean isAlreadyProcessed(String key) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key);
        return count != null && count > 0;
    }

    private void markProcessed(String key, String intentId) {
        jdbc.update(
            "INSERT INTO idempotency_keys (key, intent_id, processed_at) VALUES (?, ?, NOW()) "
            + "ON CONFLICT (key) DO NOTHING",
            key, intentId);
    }
}
