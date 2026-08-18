package io.synanton.commitix.demo.domain.model;

import io.synanton.commitix.core.domain.model.Payload;

/**
 * Payload for the {@code INVENTORY_RESERVE@v1} operation.
 * Content type is the FQCN, used by {@link io.synanton.commitix.runtime.adapter.out.JacksonPayloadSerializer}.
 */
public record InventoryReservePayload(
    String orderId,
    String productId,
    int quantity
) implements Payload {

    @Override
    public String contentType() {
        return getClass().getName();
    }
}
