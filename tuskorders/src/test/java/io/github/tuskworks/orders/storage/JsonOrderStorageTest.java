package io.github.tuskworks.orders.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tuskworks.orders.model.Order;
import io.github.tuskworks.orders.model.OrderStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonOrderStorageTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    @TempDir
    Path dir;

    private static Order order(int id, int delivered, OrderStatus status) {
        return new Order(id, UUID.randomUUID(), "Steve", "minecraft:diamond", 64, delivered, 1, 50_00,
                1_000L, 2_000L, status);
    }

    @Test
    void roundTripsOrders() throws Exception {
        Order a = order(1, 10, OrderStatus.ACTIVE);
        Order b = order(2, 64, OrderStatus.COMPLETED);
        JsonOrderStorage storage = new JsonOrderStorage(dir, LOGGER);
        storage.save(a);
        storage.save(b);
        storage.close();

        List<Order> loaded = new JsonOrderStorage(dir, LOGGER).loadAll();

        assertEquals(2, loaded.size());
        assertTrue(loaded.contains(a));
        assertTrue(loaded.contains(b));
    }

    @Test
    void laterWritesWinAndDeletesRemoveFile() throws Exception {
        JsonOrderStorage storage = new JsonOrderStorage(dir, LOGGER);
        storage.save(order(1, 5, OrderStatus.ACTIVE));
        Order latest = order(1, 20, OrderStatus.ACTIVE);
        storage.save(latest);
        storage.save(order(2, 5, OrderStatus.ACTIVE));
        storage.delete(2);
        storage.close();

        assertEquals(List.of(latest), new JsonOrderStorage(dir, LOGGER).loadAll());
    }

    @Test
    void nextIdRoundTripsAndIsNotLoadedAsAnOrder() throws Exception {
        JsonOrderStorage storage = new JsonOrderStorage(dir, LOGGER);
        assertEquals(1, storage.loadNextId(), "nothing saved yet");
        Order a = order(4, 10, OrderStatus.ACTIVE);
        storage.save(a);
        storage.saveNextId(5);
        storage.saveNextId(9);
        storage.close();

        JsonOrderStorage reopened = new JsonOrderStorage(dir, LOGGER);
        assertEquals(9, reopened.loadNextId());
        assertEquals(List.of(a), reopened.loadAll());
    }

    @Test
    void unreadableNextIdFallsBackToOne() throws Exception {
        Files.writeString(dir.resolve("next-id.txt"), "garbage");

        assertEquals(1, new JsonOrderStorage(dir, LOGGER).loadNextId());
    }

    @Test
    void skipsCorruptFiles() throws Exception {
        Files.writeString(dir.resolve("7.json"), "{ not json");
        Files.writeString(dir.resolve("8.json"), "{\"id\":8,\"amount\":-1}");

        assertTrue(new JsonOrderStorage(dir, LOGGER).loadAll().isEmpty());
    }
}
