package io.synanton.commitix.demo.domain.handler;

import io.synanton.commitix.core.domain.model.ExecutionContext;
import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.port.IntentHandler;
import io.synanton.commitix.demo.adapter.out.NotificationClient;
import io.synanton.commitix.demo.domain.model.DemoOperations;
import io.synanton.commitix.demo.domain.model.WarehouseNotifyPayload;
import io.synanton.commitix.runtime.config.CommitixAutoConfiguration.OperationKeyProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Handles {@code WAREHOUSE_NOTIFY@v1} intents.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarehouseNotifyHandler implements IntentHandler, OperationKeyProvider {

    private final NotificationClient notificationClient;

    @Override
    public String operationKey() {
        return DemoOperations.WAREHOUSE_NOTIFY_KEY;
    }

    @Override
    public ExecutionResult execute(Intent intent, ExecutionContext context) {
        WarehouseNotifyPayload payload = (WarehouseNotifyPayload) intent.payload();

        notificationClient.notifyWarehouse(
            payload.orderId(), payload.productId(), payload.quantity(), payload.warehouseId());

        log.info("Warehouse notified for orderId={}", payload.orderId());
        return new ExecutionResult.Success(null);
    }
}
