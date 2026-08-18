package io.synanton.commitix.demo.adapter.in.rest;

/** REST request body for {@code POST /orders}. */
public record CreateOrderRequest(
    String customerId,
    String productId,
    int quantity
) {
}
