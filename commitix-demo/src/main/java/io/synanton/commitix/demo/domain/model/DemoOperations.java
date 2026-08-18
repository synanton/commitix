package io.synanton.commitix.demo.domain.model;

import io.synanton.commitix.core.domain.model.Operation;

/** Well-known operations used in the demo application. */
public final class DemoOperations {

    public static final Operation INVENTORY_RESERVE = new Operation(
        "INVENTORY_RESERVE", "v1", "Reserve inventory for an order"
    );

    public static final Operation WAREHOUSE_NOTIFY = new Operation(
        "WAREHOUSE_NOTIFY", "v1", "Notify warehouse about a new order"
    );

    public static final String INVENTORY_RESERVE_KEY = "INVENTORY_RESERVE@v1";
    public static final String WAREHOUSE_NOTIFY_KEY = "WAREHOUSE_NOTIFY@v1";

    private DemoOperations() {
    }
}
