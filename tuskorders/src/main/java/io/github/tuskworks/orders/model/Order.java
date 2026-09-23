package io.github.tuskworks.orders.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A buy order: the owner pays {@code priceEach} for every item delivered, up to {@code amount}.
 * Immutable so it can be read from any thread; the service swaps in updated copies.
 *
 * @param item      namespaced item key, e.g. {@code minecraft:diamond}
 * @param delivered items handed in so far (and already paid out)
 * @param collected delivered items the owner has taken out of the order
 * @param priceEach price per item in cents
 * @param expiresAt epoch millis, or 0 for never
 */
public record Order(
        int id,
        UUID owner,
        String ownerName,
        String item,
        int amount,
        int delivered,
        int collected,
        long priceEach,
        long createdAt,
        long expiresAt,
        OrderStatus status) {

    public Order {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(status, "status");
        if (amount <= 0 || delivered < 0 || delivered > amount || collected < 0 || collected > delivered) {
            throw new IllegalArgumentException("Inconsistent order counts: amount=" + amount
                    + " delivered=" + delivered + " collected=" + collected);
        }
        if (priceEach <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
    }

    public int remaining() {
        return amount - delivered;
    }

    /** Delivered items still waiting for the owner. */
    public int uncollected() {
        return delivered - collected;
    }

    public boolean active() {
        return status == OrderStatus.ACTIVE;
    }

    /** Money currently held for the unfilled part of this order. */
    public long escrow() {
        return active() ? remaining() * priceEach : 0;
    }

    public long totalValue() {
        return amount * priceEach;
    }

    /** Finished orders with nothing left to collect can be deleted. */
    public boolean archivable() {
        return !active() && uncollected() == 0;
    }

    public boolean expired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    public Order withDelivered(int delivered) {
        OrderStatus next = delivered == amount && status == OrderStatus.ACTIVE ? OrderStatus.COMPLETED : status;
        return new Order(id, owner, ownerName, item, amount, delivered, collected, priceEach, createdAt, expiresAt, next);
    }

    public Order withCollected(int collected) {
        return new Order(id, owner, ownerName, item, amount, delivered, collected, priceEach, createdAt, expiresAt, status);
    }

    public Order withStatus(OrderStatus status) {
        return new Order(id, owner, ownerName, item, amount, delivered, collected, priceEach, createdAt, expiresAt, status);
    }

    public Order withOwnerName(String ownerName) {
        return new Order(id, owner, ownerName, item, amount, delivered, collected, priceEach, createdAt, expiresAt, status);
    }
}
