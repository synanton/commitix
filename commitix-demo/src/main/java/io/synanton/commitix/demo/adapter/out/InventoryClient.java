package io.synanton.commitix.demo.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Stub inventory service client for the demo. */
@Slf4j
@Component
public class InventoryClient {

    public void reserveStock(String orderId, String productId, int quantity) {
        log.info("STUB: Reserving {} units of {} for order {}", quantity, productId, orderId);
    }
}
