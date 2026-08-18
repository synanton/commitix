package io.synanton.commitix.demo.domain.model;

import io.synanton.commitix.core.domain.model.Payload;

/**
 * Payload for the {@code WAREHOUSE_NOTIFY@v1} operation.
 */
public record WarehouseNotifyPayload(
    String orderId,
    String productId,
    int quantity,
    String warehouseId
) implements Payload {

    @Override
    public String contentType() {
        return getClass().getName();
    }
}
