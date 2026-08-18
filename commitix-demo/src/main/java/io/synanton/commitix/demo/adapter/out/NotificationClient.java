package io.synanton.commitix.demo.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Stub notification/warehouse client for the demo. */
@Slf4j
@Component
public class NotificationClient {

    public void notifyWarehouse(String orderId, String productId, int quantity, String warehouseId) {
        log.info("STUB: Notifying warehouse {} about order {} ({} x {})",
            warehouseId, orderId, quantity, productId);
    }
}
