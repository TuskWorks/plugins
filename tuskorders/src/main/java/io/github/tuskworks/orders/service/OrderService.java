package io.github.tuskworks.orders.service;

import io.github.tuskworks.orders.model.Order;
import io.github.tuskworks.orders.model.OrderStatus;
import io.github.tuskworks.orders.money.Money;
import io.github.tuskworks.orders.storage.OrderStorage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Owns every order and the money held for them. Order state changes happen under one lock,
 * but economy calls are made outside it so a slow economy plugin can't stall other regions;
 * each money step is ordered so a failed payment can be rolled back without losing anything.
 */
public final class OrderService {

    /** Result of a delivery; {@code accepted} items were taken and must not be returned. */
    public record Delivery(Outcome outcome, int accepted) {
    }

    private final Map<Integer, Order> orders = new ConcurrentHashMap<>();
    /** Delivered items whose payout hasn't cleared yet; they can't be collected until it does. */
    private final Map<Integer, Integer> inFlight = new HashMap<>();
    private final Object lock = new Object();
    private final OrderStorage storage;
    private final Bank bank;
    private final Clock clock;
    private final Logger logger;
    private volatile OrderSettings settings;
    private volatile Money money;
    private int nextId = 1;

    public OrderService(OrderSettings settings, Money money, OrderStorage storage, Bank bank, Clock clock, Logger logger) {
        this.settings = settings;
        this.money = money;
        this.storage = storage;
        this.bank = bank;
        this.clock = clock;
        this.logger = logger;
    }

    public void load(Collection<Order> loaded) {
        synchronized (lock) {
            orders.clear();
            for (Order order : loaded) {
                orders.put(order.id(), order);
                nextId = Math.max(nextId, order.id() + 1);
            }
        }
    }

    public void settings(OrderSettings settings, Money money) {
        this.settings = settings;
        this.money = money;
    }

    public OrderSettings settings() {
        return settings;
    }

    public Money money() {
        return money;
    }

    // ---- queries ------------------------------------------------------------------------

    public Optional<Order> get(int id) {
        return Optional.ofNullable(orders.get(id));
    }

    /** Active orders, optionally filtered by item key text such as {@code "oak log"}. */
    public List<Order> active(OrderSort sort, String filter) {
        String needle = normalize(filter);
        return orders.values().stream()
                .filter(Order::active)
                .filter(o -> needle.isEmpty() || normalize(o.item()).contains(needle))
                .sorted(sort.comparator())
                .toList();
    }

    /** The player's orders that still matter to them: open ones and ones with items to collect. */
    public List<Order> byOwner(UUID owner) {
        return orders.values().stream()
                .filter(o -> o.owner().equals(owner))
                .sorted(OrderSort.RECENT.comparator())
                .toList();
    }

    public int activeCount(UUID owner) {
        return (int) orders.values().stream().filter(o -> o.active() && o.owner().equals(owner)).count();
    }

    public int uncollectedCount(UUID owner) {
        return orders.values().stream().filter(o -> o.owner().equals(owner)).mapToInt(Order::uncollected).sum();
    }

    // ---- actions ------------------------------------------------------------------------

    /** Checks amount and price against the rules, so bad input is caught before anything is shown. */
    public Optional<Outcome> validate(int amount, long priceEach) {
        OrderSettings s = settings;
        Money m = money;
        if (amount <= 0 || amount > s.maxAmount()) {
            return Optional.of(Outcome.fail("create.invalid-amount", "max", String.valueOf(s.maxAmount())));
        }
        if (priceEach < s.minPriceEach()) {
            return Optional.of(Outcome.fail("create.price-too-low", "min", m.format(s.minPriceEach())));
        }
        if (priceEach > s.maxPriceEach()) {
            return Optional.of(Outcome.fail("create.price-too-high", "max", m.format(s.maxPriceEach())));
        }
        try {
            long total = Math.multiplyExact(priceEach, amount);
            long charge = Math.addExact(total, Money.percentOf(total, s.creationFeePercent()));
            if (charge > OrderSettings.MAX_ORDER_TOTAL) {
                return Optional.of(Outcome.fail("create.too-expensive"));
            }
        } catch (ArithmeticException e) {
            return Optional.of(Outcome.fail("create.too-expensive"));
        }
        return Optional.empty();
    }

    /**
     * Places an order and takes its full value (plus any creation fee) into escrow.
     *
     * @param activeLimit how many open orders this player may have
     */
    public Outcome create(UUID owner, String ownerName, String item, int amount, long priceEach, int activeLimit) {
        Optional<Outcome> invalid = validate(amount, priceEach);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        OrderSettings s = settings;
        Money m = money;
        long total = priceEach * amount;
        long charge = total + Money.percentOf(total, s.creationFeePercent());
        if (activeCount(owner) >= activeLimit) {
            return Outcome.fail("create.limit-reached", "limit", String.valueOf(activeLimit));
        }
        if (!bank.withdraw(owner, charge)) {
            return Outcome.fail("create.no-money", "cost", m.format(charge));
        }
        Order order;
        synchronized (lock) {
            // A second order may have slipped in while the economy call ran
            if (activeCount(owner) >= activeLimit) {
                order = null;
            } else {
                long now = clock.millis();
                long expiresAt = s.expiry().isZero() ? 0 : now + s.expiry().toMillis();
                order = new Order(nextId++, owner, ownerName, item, amount, 0, 0, priceEach, now, expiresAt,
                        OrderStatus.ACTIVE);
                orders.put(order.id(), order);
                storage.save(order);
            }
        }
        if (order == null) {
            refund(owner, charge, "order limit race");
            return Outcome.fail("create.limit-reached", "limit", String.valueOf(activeLimit));
        }
        return Outcome.ok("create.success",
                "id", String.valueOf(order.id()),
                "amount", String.valueOf(amount),
                "item_key", item,
                "price", m.format(priceEach),
                "total", m.format(charge));
    }

    /**
     * Hands {@code offered} items to an order and pays the deliverer for the ones it needed.
     * Items beyond what the order still wants are not accepted and belong to the caller again.
     */
    public Delivery deliver(int orderId, UUID deliverer, int offered) {
        if (offered <= 0) {
            return new Delivery(Outcome.fail("deliver.nothing"), 0);
        }
        Order before;
        int accepted;
        synchronized (lock) {
            before = orders.get(orderId);
            if (before == null || !before.active()) {
                return new Delivery(Outcome.fail("deliver.unavailable"), 0);
            }
            if (before.owner().equals(deliverer)) {
                return new Delivery(Outcome.fail("deliver.own-order"), 0);
            }
            accepted = Math.min(offered, before.remaining());
            put(before.withDelivered(before.delivered() + accepted));
            inFlight.merge(orderId, accepted, Integer::sum);
        }
        long payout = accepted * before.priceEach();
        long tax = Money.percentOf(payout, settings.deliveryTaxPercent());
        boolean paid = bank.deposit(deliverer, payout - tax);
        synchronized (lock) {
            inFlight.computeIfPresent(orderId, (id, n) -> n == accepted ? null : n - accepted);
            if (!paid) {
                rollbackDelivery(orderId, accepted, before.priceEach());
            }
        }
        if (!paid) {
            return new Delivery(Outcome.fail("deliver.payment-failed"), 0);
        }
        Money m = money;
        return new Delivery(Outcome.ok("deliver.success",
                "id", String.valueOf(orderId),
                "accepted", String.valueOf(accepted),
                "item_key", before.item(),
                "payout", m.format(payout - tax),
                "tax", m.format(tax),
                "owner", before.ownerName(),
                "remaining", String.valueOf(before.remaining() - accepted)), accepted);
    }

    /** Must hold {@link #lock}. In-flight items were never collectable, so the order still holds them. */
    private void rollbackDelivery(int orderId, int accepted, long priceEach) {
        Order current = orders.get(orderId);
        if (current == null) {
            logger.severe("Order #" + orderId + " vanished while rolling back a failed payout");
            return;
        }
        Order reverted = new Order(current.id(), current.owner(), current.ownerName(), current.item(),
                current.amount(), current.delivered() - accepted, current.collected(), current.priceEach(),
                current.createdAt(), current.expiresAt(),
                current.status() == OrderStatus.COMPLETED ? OrderStatus.ACTIVE : current.status());
        put(reverted);
        if (!reverted.active()) {
            // Cancelled or expired meanwhile: that refund didn't cover these items
            refund(reverted.owner(), accepted * priceEach, "rolled back delivery on closed order #" + orderId);
            archiveIfDone(orderId);
        }
    }

    /** Closes an order and refunds its unfilled part. Delivered items stay collectable. */
    public Outcome cancel(int orderId, UUID actor, boolean admin) {
        Order before;
        synchronized (lock) {
            before = orders.get(orderId);
            if (before == null) {
                return Outcome.fail("cancel.not-found", "id", String.valueOf(orderId));
            }
            if (!admin && !before.owner().equals(actor)) {
                return Outcome.fail("cancel.not-owner");
            }
            if (!before.active()) {
                return Outcome.fail("cancel.not-active");
            }
            put(before.withStatus(OrderStatus.CANCELLED));
        }
        long refund = before.escrow();
        if (!bank.deposit(before.owner(), refund)) {
            reopen(orderId, OrderStatus.CANCELLED);
            return Outcome.fail("cancel.refund-failed");
        }
        synchronized (lock) {
            archiveIfDone(orderId);
        }
        return Outcome.ok("cancel.success",
                "id", String.valueOf(orderId),
                "refund", money.format(refund),
                "uncollected", String.valueOf(before.uncollected()),
                "item_key", before.item());
    }

    /**
     * Moves up to {@code space} delivered items out of the order for its owner.
     *
     * @return how many items the caller must now give to the owner
     */
    public int collect(int orderId, UUID owner, int space) {
        synchronized (lock) {
            Order order = orders.get(orderId);
            if (order == null || !order.owner().equals(owner) || space <= 0) {
                return 0;
            }
            int take = Math.min(order.uncollected() - inFlight.getOrDefault(orderId, 0), space);
            if (take <= 0) {
                return 0;
            }
            put(order.withCollected(order.collected() + take));
            archiveIfDone(orderId);
            return take;
        }
    }

    /** Expires overdue orders and refunds them; returns the ones that were closed. */
    public List<Order> expireOverdue() {
        long now = clock.millis();
        List<Order> expired = new ArrayList<>();
        for (Order candidate : List.copyOf(orders.values())) {
            if (!candidate.active() || !candidate.expired(now)) {
                continue;
            }
            Order before;
            synchronized (lock) {
                before = orders.get(candidate.id());
                if (before == null || !before.active()) {
                    continue;
                }
                put(before.withStatus(OrderStatus.EXPIRED));
            }
            if (bank.deposit(before.owner(), before.escrow())) {
                synchronized (lock) {
                    expired.add(orders.getOrDefault(before.id(), before.withStatus(OrderStatus.EXPIRED)));
                    archiveIfDone(before.id());
                }
            } else {
                // Leave it open and try again on the next sweep
                reopen(before.id(), OrderStatus.EXPIRED);
                logger.warning("Could not refund expiring order #" + before.id() + "; will retry");
            }
        }
        return expired;
    }

    /** Keeps the displayed owner name current after a rename. */
    public void updateOwnerName(UUID owner, String name) {
        synchronized (lock) {
            for (Order order : List.copyOf(orders.values())) {
                if (order.owner().equals(owner) && !order.ownerName().equals(name)) {
                    put(order.withOwnerName(name));
                }
            }
        }
    }

    private void reopen(int orderId, OrderStatus expected) {
        synchronized (lock) {
            Order current = orders.get(orderId);
            if (current != null && current.status() == expected) {
                // Deliveries were blocked while it was closed, so the escrow is unchanged
                put(current.withStatus(OrderStatus.ACTIVE));
            }
        }
    }

    private void refund(UUID player, long cents, String reason) {
        if (!bank.deposit(player, cents)) {
            logger.severe("Failed to refund " + money.format(cents) + " to " + player + " (" + reason
                    + "); please compensate manually");
        }
    }

    /** Must hold {@link #lock}. */
    private void put(Order order) {
        orders.put(order.id(), order);
        storage.save(order);
    }

    /** Must hold {@link #lock}. Deletes a finished order once nothing is left in it. */
    private void archiveIfDone(int orderId) {
        Order order = orders.get(orderId);
        if (order != null && order.archivable() && !inFlight.containsKey(orderId)) {
            orders.remove(orderId);
            storage.delete(orderId);
        }
    }

    private static String normalize(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        return s.replace('_', ' ').replaceAll("\\s+", " ");
    }
}
