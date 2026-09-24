package io.github.tuskworks.orders.config;

import io.github.tuskworks.orders.money.Money;
import io.github.tuskworks.orders.service.OrderSettings;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

public record PluginConfig(
        String language,
        List<String> aliases,
        String currencySymbol,
        OrderSettings orders,
        int defaultActiveOrders,
        List<String> blacklist) {

    public Money money() {
        return new Money(currencySymbol);
    }

    public static PluginConfig from(ConfigurationSection c, Logger logger) {
        long minPrice = price(c, "orders.min-price-each", "0.01", logger);
        long maxPrice = Math.max(minPrice, price(c, "orders.max-price-each", "1000000000", logger));
        OrderSettings orders = new OrderSettings(
                Math.max(1, c.getInt("orders.max-amount", 100_000)),
                minPrice,
                maxPrice,
                Duration.ofHours(Math.max(0, Math.round(c.getDouble("orders.expire-after-days", 7) * 24))),
                clampPercent(c.getDouble("orders.creation-fee-percent", 0)),
                clampPercent(c.getDouble("orders.delivery-tax-percent", 0)));
        return new PluginConfig(
                c.getString("language", "en"),
                List.copyOf(c.getStringList("command.aliases")),
                c.getString("currency-symbol", "$"),
                orders,
                Math.max(0, c.getInt("limits.default-active-orders", 5)),
                List.copyOf(c.getStringList("blacklist")));
    }

    private static long price(ConfigurationSection c, String path, String fallback, Logger logger) {
        String raw = c.getString(path, fallback);
        OptionalLong parsed = Money.parse(raw);
        if (parsed.isEmpty()) {
            logger.warning("Invalid " + path + " '" + raw + "', using " + fallback);
            return Money.parse(fallback).orElseThrow();
        }
        return parsed.getAsLong();
    }

    private static double clampPercent(double percent) {
        return Math.min(100, Math.max(0, percent));
    }
}
