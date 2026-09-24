package io.github.tuskworks.orders.storage;

import io.github.tuskworks.orders.model.Order;
import java.io.IOException;
import java.util.List;

public interface OrderStorage {

    List<Order> loadAll() throws IOException;

    /** Persists the order; may complete asynchronously but writes stay in call order. */
    void save(Order order);

    void delete(int orderId);

    /** The next order id to hand out as last saved, or 1 if none was saved yet. */
    int loadNextId() throws IOException;

    /**
     * Remembers the next order id, so ids of finished (deleted) orders are never handed out
     * again after a restart. Written in call order with {@link #save}.
     */
    void saveNextId(int nextId);

    /** Waits for pending writes. */
    void close();
}
