package io.github.tuskworks.orders.service;

import io.github.tuskworks.orders.model.Order;
import java.util.Comparator;

public enum OrderSort {
    /** Highest price per item first. */
    PRICE(Comparator.comparingLong(Order::priceEach).reversed()),
    /** Newest first. */
    RECENT(Comparator.comparingLong(Order::createdAt).reversed()),
    /** Most money still up for grabs first. */
    PAYOUT(Comparator.comparingLong(Order::escrow).reversed());

    private final Comparator<Order> comparator;

    OrderSort(Comparator<Order> comparator) {
        this.comparator = comparator.thenComparingInt(Order::id);
    }

    public Comparator<Order> comparator() {
        return comparator;
    }

    public OrderSort next() {
        OrderSort[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
