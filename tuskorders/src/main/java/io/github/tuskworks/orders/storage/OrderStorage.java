package io.github.tuskworks.orders.storage;

import io.github.tuskworks.orders.model.Order;
import java.io.IOException;
import java.util.List;

public interface OrderStorage {

    List<Order> loadAll() throws IOException;

    /** Persists the order; may complete asynchronously but writes stay in call order. */
    void save(Order order);

    void delete(int orderId);

    /** Waits for pending writes. */
    void close();
}
