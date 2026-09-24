package io.github.tuskworks.orders.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.tuskworks.orders.model.Order;
import io.github.tuskworks.orders.model.OrderStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One JSON file per order, written atomically on a single background thread so writes
 * for the same order are applied in order.
 */
public final class JsonOrderStorage implements OrderStorage {

    private static final int FORMAT_VERSION = 1;
    /** Not *.json, so {@link #loadAll} never mistakes it for an order. */
    private static final String NEXT_ID_FILE = "next-id.txt";

    private final Path dir;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TuskOrders-storage");
        t.setDaemon(true);
        return t;
    });

    public JsonOrderStorage(Path dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    @Override
    public List<Order> loadAll() throws IOException {
        Files.createDirectories(dir);
        List<Order> orders = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try {
                    OrderData data = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), OrderData.class);
                    orders.add(data.toOrder());
                } catch (JsonParseException | IllegalArgumentException | NullPointerException e) {
                    logger.log(Level.SEVERE, "Skipping unreadable order file " + file.getFileName(), e);
                }
            }
        }
        return orders;
    }

    @Override
    public void save(Order order) {
        String json = gson.toJson(OrderData.of(order));
        Path target = file(order.id());
        submit(() -> writeAtomically(target, json));
    }

    @Override
    public void delete(int orderId) {
        submit(() -> Files.deleteIfExists(file(orderId)));
    }

    @Override
    public int loadNextId() throws IOException {
        Path file = dir.resolve(NEXT_ID_FILE);
        if (!Files.exists(file)) {
            return 1;
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            // The ids of the loaded orders still keep the sequence going
            logger.warning("Ignoring unreadable " + NEXT_ID_FILE + " ('" + raw + "')");
            return 1;
        }
    }

    @Override
    public void saveNextId(int nextId) {
        String text = String.valueOf(nextId);
        submit(() -> writeAtomically(dir.resolve(NEXT_ID_FILE), text));
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.createDirectories(dir);
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.severe("Timed out waiting for order data to be written");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path file(int orderId) {
        return dir.resolve(orderId + ".json");
    }

    private void submit(IoTask task) {
        writer.execute(() -> {
            try {
                task.run();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to write order data", e);
            }
        });
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws IOException;
    }

    // Plain DTO keeps the on-disk format independent from the domain record.
    @SuppressWarnings("unused")
    private static final class OrderData {
        int format = FORMAT_VERSION;
        int id;
        String owner;
        String ownerName;
        String item;
        int amount;
        int delivered;
        int collected;
        long priceEachCents;
        long createdAt;
        long expiresAt;
        String status;

        static OrderData of(Order o) {
            OrderData d = new OrderData();
            d.id = o.id();
            d.owner = o.owner().toString();
            d.ownerName = o.ownerName();
            d.item = o.item();
            d.amount = o.amount();
            d.delivered = o.delivered();
            d.collected = o.collected();
            d.priceEachCents = o.priceEach();
            d.createdAt = o.createdAt();
            d.expiresAt = o.expiresAt();
            d.status = o.status().name();
            return d;
        }

        Order toOrder() {
            return new Order(id, UUID.fromString(owner), ownerName, item, amount, delivered, collected,
                    priceEachCents, createdAt, expiresAt, OrderStatus.valueOf(status));
        }
    }
}
