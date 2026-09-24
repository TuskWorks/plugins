package io.github.tuskworks.orders.service;

import io.github.tuskworks.orders.model.Order;
import io.github.tuskworks.orders.storage.OrderStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TestSupport {

    private TestSupport() {
    }

    static final class FakeBank implements Bank {
        final Map<UUID, Long> balances = new HashMap<>();
        boolean failDeposits;
        Runnable beforeDeposit = () -> { };

        long balance(UUID player) {
            return balances.getOrDefault(player, 0L);
        }

        void give(UUID player, long cents) {
            balances.merge(player, cents, Long::sum);
        }

        @Override
        public boolean withdraw(UUID player, long cents) {
            if (balance(player) < cents) {
                return false;
            }
            balances.merge(player, -cents, Long::sum);
            return true;
        }

        @Override
        public boolean deposit(UUID player, long cents) {
            beforeDeposit.run();
            if (failDeposits) {
                return false;
            }
            balances.merge(player, cents, Long::sum);
            return true;
        }
    }

    static final class MemoryStorage implements OrderStorage {
        final Map<Integer, Order> saved = new HashMap<>();
        int nextId = 1;

        @Override
        public List<Order> loadAll() {
            return List.copyOf(saved.values());
        }

        @Override
        public int loadNextId() {
            return nextId;
        }

        @Override
        public void saveNextId(int nextId) {
            this.nextId = nextId;
        }

        @Override
        public void save(Order order) {
            saved.put(order.id(), order);
        }

        @Override
        public void delete(int orderId) {
            saved.remove(orderId);
        }

        @Override
        public void close() {
        }
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-24T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
