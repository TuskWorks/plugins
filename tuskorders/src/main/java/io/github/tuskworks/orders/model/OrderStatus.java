package io.github.tuskworks.orders.model;

public enum OrderStatus {
    /** Accepting deliveries; the unfilled part is held in escrow. */
    ACTIVE,
    /** Fully delivered. */
    COMPLETED,
    /** Cancelled by the owner or an admin; the unfilled part was refunded. */
    CANCELLED,
    /** Ran out of time; the unfilled part was refunded. */
    EXPIRED
}
