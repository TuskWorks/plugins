package io.github.tuskworks.orders.service;

import java.time.Duration;

/**
 * Rules the service enforces on every order.
 *
 * @param expiry              how long an order stays open, or {@link Duration#ZERO} for no limit
 * @param creationFeePercent  charged on top of the order total when it is placed; never refunded
 * @param deliveryTaxPercent  taken from each payout to the deliverer
 */
public record OrderSettings(
        int maxAmount,
        long minPriceEach,
        long maxPriceEach,
        Duration expiry,
        double creationFeePercent,
        double deliveryTaxPercent) {

    /** Hard ceiling on a single order's value so cents never get near {@code Long.MAX_VALUE}. */
    public static final long MAX_ORDER_TOTAL = 1_000_000_000_000_000L;
}
